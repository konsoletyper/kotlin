/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.js.translate.ir

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.js.ir.JsirExpression
import org.jetbrains.kotlin.js.ir.JsirStatement
import org.jetbrains.kotlin.js.ir.JsirVariable
import org.jetbrains.kotlin.js.ir.makeReference
import org.jetbrains.kotlin.js.resolve.diagnostics.ErrorsJs
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getTextWithLocation
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.BindingContextUtils
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.constants.NullValue
import org.jetbrains.kotlin.resolve.constants.evaluate.ConstantExpressionEvaluator
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.kotlin.types.expressions.ExpressionTypingUtils.isFunctionExpression
import org.jetbrains.kotlin.types.expressions.ExpressionTypingUtils.isFunctionLiteral

class JsirGenerator(private var bindingTrace: BindingTrace) : KtVisitor<JsirExpression, JsirContext>() {
    private var resultingStatements = mutableListOf<JsirStatement>()
    private lateinit var currentFunction: FunctionDescriptor
    private lateinit var currentDescriptor: DeclarationDescriptor
    private var currentClass: ClassDescriptor? = null
    private var localVariables = mutableMapOf<VariableDescriptor, JsirVariable>()
    private var currentSource: PsiElement? = null

    private val context: JsirContext = object : JsirContext {
        override val bindingContext: BindingContext
            get() = bindingTrace.bindingContext

        override val declaration: DeclarationDescriptor
            get() = currentDescriptor

        override val function: FunctionDescriptor
            get() = currentFunction

        override val classDescriptor: ClassDescriptor?
            get() = currentClass

        override fun append(statement: JsirStatement): JsirContext {
            resultingStatements.add(statement.apply { source = currentSource })
            return this
        }

        override fun <T> withSource(source: PsiElement?, action: () -> T): T {
            val backup = currentSource
            return try {
                currentSource = source
                action()
            }
            finally {
                currentSource = backup
            }
        }

        override fun getVariable(descriptor: VariableDescriptor) = getVariableAccessor(descriptor)

        override fun nestedBlock(body: MutableList<JsirStatement>, action: () -> Unit) {
            val backup = resultingStatements
            try {
                resultingStatements = body
                action()
            }
            finally {
                resultingStatements = backup
            }
        }

        override fun translate(expression: KtExpression): JsirExpression {
            val visitor = this@JsirGenerator
            return expression.accept(visitor, visitor.context)
        }
    }

    override fun visitKtElement(expression: KtElement, context: JsirContext): JsirExpression {
        bindingTrace.report(ErrorsJs.NOT_SUPPORTED.on(expression, expression))
        return JsirExpression.Null
    }

    fun traverseContainer(jetClass: KtDeclarationContainer, context: JsirContext) {
        for (declaration in jetClass.declarations) {
            declaration.accept(this, context)
        }
    }

    override fun visitConstantExpression(expression: KtConstantExpression, context: JsirContext): JsirExpression {
        return translateConstantExpression(expression, context).apply { source = expression }
    }

    private fun translateConstantExpression(expression: KtConstantExpression, context: JsirContext): JsirExpression {
        val compileTimeValue = ConstantExpressionEvaluator.getConstant(expression, context.bindingContext) ?:
                               error("Expression is not compile time value: " + expression.getTextWithLocation() + " ")
        val expectedType = context.bindingContext.getType(expression)
        val constant = compileTimeValue.toConstantValue(expectedType ?: TypeUtils.NO_EXPECTED_TYPE)
        if (constant is NullValue) return JsirExpression.Null

        return JsirExpression.Constant(constant.value!!)
    }

    override fun visitBlockExpression(expression: KtBlockExpression, data: JsirContext): JsirExpression {
        return context.withSource(expression) {
            if (expression.statements.isNotEmpty()) {
                for (statement in expression.statements.dropLast(1)) {
                    context.translate(statement)
                }
                context.translate(expression.statements.last())
            }
            else {
                JsirExpression.Null
            }
        }
    }

    override fun visitReturnExpression(expression: KtReturnExpression, data: JsirContext): JsirExpression {
        context.withSource(expression) {
            val returnValue = expression.returnedExpression?.accept(this, data)
            val target = getNonLocalReturnTarget(expression) ?: currentFunction
            context.append(JsirStatement.Return(returnValue, target))
        }
        return JsirExpression.Null
    }

    private fun getNonLocalReturnTarget(expression: KtReturnExpression): FunctionDescriptor? {
        var descriptor: DeclarationDescriptor? = currentDescriptor
        assert(descriptor is CallableMemberDescriptor) { "Return expression can only be inside callable declaration: " +
                                                         "${expression.getTextWithLocation()}" }
        val target = expression.getTargetLabel()

        // call inside lambda
        if (isFunctionLiteral(descriptor) || isFunctionExpression(descriptor)) {
            if (target == null) {
                if (isFunctionLiteral(descriptor)) {
                    return BindingContextUtils.getContainingFunctionSkipFunctionLiterals(descriptor, true).getFirst()
                }
            }
            else {
                val element = context.bindingContext[BindingContext.LABEL_TARGET, target]
                descriptor = context.bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, element]
            }
        }

        assert(descriptor == null || descriptor is FunctionDescriptor) { "Function descriptor expected to be target of return label: " +
                                                                         "${expression.getTextWithLocation()}" }
        return descriptor as FunctionDescriptor?
    }

    override fun visitParenthesizedExpression(expression: KtParenthesizedExpression, context: JsirContext): JsirExpression {
        val expressionInside = expression.expression
        return if (expressionInside != null) {
            expressionInside.accept(this, context)
        }
        else {
            JsirExpression.Null
        }
    }

    override fun visitBinaryExpression(expression: KtBinaryExpression, data: JsirContext?): JsirExpression {
        return context.withSource(expression) {
            BinaryExpressionJsirGenerator(context).generate(expression)
        }
    }

    private fun getVariableAccessor(descriptor: VariableDescriptor): VariableAccessor {
        val container = descriptor.containingDeclaration
        if (container !is FunctionDescriptor) {
            throw IllegalArgumentException("Can only get accessor for local variables. $descriptor is not a local variable")
        }

        val localVar = localVariables.getOrPut(descriptor) { JsirVariable(descriptor.name.asString()) }
        return object : VariableAccessor {
            override fun get() = localVar.makeReference()

            override fun set(value: JsirExpression) {
                context.assign(localVar.makeReference(), value)
            }
        }
    }
}
