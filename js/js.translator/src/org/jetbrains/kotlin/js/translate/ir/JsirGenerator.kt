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

import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.js.ir.*
import org.jetbrains.kotlin.js.resolve.diagnostics.ErrorsJs
import org.jetbrains.kotlin.js.translate.utils.BindingUtils
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getTextWithLocation
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.BindingContextUtils
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCall
import org.jetbrains.kotlin.resolve.constants.NullValue
import org.jetbrains.kotlin.resolve.constants.evaluate.ConstantExpressionEvaluator
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.kotlin.types.expressions.ExpressionTypingUtils.isFunctionExpression
import org.jetbrains.kotlin.types.expressions.ExpressionTypingUtils.isFunctionLiteral

class JsirGenerator(private val bindingTrace: BindingTrace) : KtVisitor<JsirExpression, JsirContext>() {
    val context: JsirContext = JsirContext(bindingTrace) { expr ->
        val visitor = this
        expr.accept(this, visitor.context)
    }

    override fun visitKtElement(expression: KtElement, context: JsirContext): JsirExpression {
        bindingTrace.report(ErrorsJs.NOT_SUPPORTED.on(expression, expression))
        return JsirExpression.Null
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

    override fun visitStringTemplateExpression(expression: KtStringTemplateExpression, data: JsirContext?): JsirExpression {
        val constant = BindingUtils.getCompileTimeValue(context.bindingContext, expression) as String?
        return if (constant != null) {
            JsirExpression.Constant(constant)
        }
        else {
            val templateGenerator = StringTemplateGenerator(context)
            for (entry in expression.entries) {
                entry.accept(templateGenerator)
            }
            JsirExpression.Concat(*templateGenerator.parts.toTypedArray())
        }
    }

    override fun visitBlockExpression(expression: KtBlockExpression, data: JsirContext): JsirExpression {
        return context.withSource(expression) {
            if (expression.statements.isNotEmpty()) {
                for (statement in expression.statements.dropLast(1)) {
                    context.append(context.generate(statement))
                }
                context.generate(expression.statements.last())
            }
            else {
                JsirExpression.Null
            }
        }
    }

    override fun visitReturnExpression(expression: KtReturnExpression, data: JsirContext): JsirExpression {
        context.withSource(expression) {
            val returnValue = expression.returnedExpression?.accept(this, data)
            val target = getNonLocalReturnTarget(expression) ?: context.function
            context.append(JsirStatement.Return(returnValue, target!!))
        }
        return JsirExpression.Null
    }

    override fun visitBreakExpression(expression: KtBreakExpression, data: JsirContext?): JsirExpression {
        context.withSource(expression) {
            val target = expression.getTargetLabel()?.let { context.getLabelTarget(it) } ?: context.defaultBreakTarget
            context.append(JsirStatement.Break(target!!))
        }
        return JsirExpression.Null
    }

    private fun getNonLocalReturnTarget(expression: KtReturnExpression): FunctionDescriptor? {
        var descriptor: DeclarationDescriptor? = context.declaration
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

    override fun visitBinaryExpression(expression: KtBinaryExpression, data: JsirContext): JsirExpression {
        return context.withSource(expression) {
            context.generateBinary(expression)
        }
    }

    override fun visitUnaryExpression(expression: KtUnaryExpression, data: JsirContext?): JsirExpression {
        return context.withSource(expression) {
            context.generateUnary(expression)
        }
    }

    override fun visitNamedFunction(functionPsi: KtNamedFunction, data: JsirContext): JsirExpression {
        if (!functionPsi.hasBody()) return JsirExpression.Null

        val descriptor = context.bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, functionPsi] as FunctionDescriptor
        context.nestedFunction(descriptor) {
            val function = JsirFunction(descriptor)
            for (parameter in descriptor.valueParameters) {
                function.parameters += context.getVariable(parameter).localVariable
            }

            context.nestedBlock(function.body) {
                val returnValue = context.generate(functionPsi.bodyExpression!!)
                context.append(JsirStatement.Return(returnValue, descriptor))
            }

            context.pool.addFunction(descriptor, function)
        }

        return JsirExpression.Null
    }

    override fun visitKtFile(file: KtFile, data: JsirContext): JsirExpression {
        for (declaration in file.declarations) {
            context.generate(declaration)
        }

        return JsirExpression.Null
    }

    override fun visitCallExpression(expression: KtCallExpression, data: JsirContext?): JsirExpression {
        val resolvedCall = expression.getResolvedCall(context.bindingContext)!!
        val callee = expression.calleeExpression!!
        val qualifier = if (callee is KtDotQualifiedExpression) {
            callee.receiverExpression
        }
        else {
            null
        }
        return context.generateInvocation(qualifier, resolvedCall)
    }

    override fun visitProperty(property: KtProperty, data: JsirContext): JsirExpression {
        val descriptor = context.bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, property]
        return if (context.declaration is FunctionDescriptor) {
            return context.getVariable(descriptor as VariableDescriptor).get()
        }
        else {
            JsirExpression.Null
        }
    }

    override fun visitIfExpression(expression: KtIfExpression, data: JsirContext): JsirExpression {
        val temporary = JsirVariable()
        context.withSource(expression) {
            val conditionPsi = expression.condition!!
            val statement = context.withSource(conditionPsi) {
                JsirStatement.If(context.generate(conditionPsi))
            }
            context.append(statement)
            context.nestedBlock(statement.thenBody) {
                context.assign(temporary.makeReference(), context.generate(expression.then!!))
            }
            expression.`else`?.let { elseExpr ->
                context.nestedBlock(statement.elseBody) {
                    context.assign(temporary.makeReference(), context.generate(elseExpr))
                }
            }
        }
        return temporary.makeReference()
    }

    override fun visitDoWhileExpression(expression: KtDoWhileExpression, data: JsirContext): JsirExpression {
        context.withSource(expression) {
            val statement = JsirStatement.DoWhile(JsirExpression.True)
            context.append(statement)
            context.nestedLabel(null, statement, true) {
                context.nestedBlock(statement.body) {
                    val block = JsirStatement.Block()
                    context.append(block)
                    context.nestedBlock(block.body) {
                        context.withContinueReplacement(statement, block) {
                            expression.body?.let { context.generate(it) }
                        }
                    }
                    context.withSource(expression.condition) {
                        val conditionalBreak = JsirStatement.If(context.generate(expression.condition!!).negate())
                        context.append(conditionalBreak)
                        context.nestedBlock(conditionalBreak.thenBody) {
                            context.append(JsirStatement.Break(statement))
                        }
                    }
                }
            }
        }

        return JsirExpression.Null
    }

    override fun visitTryExpression(expression: KtTryExpression, data: JsirContext?): JsirExpression {
        val temporary = JsirVariable()
        context.withSource(expression) {
            val statement = JsirStatement.Try()
            context.nestedBlock(statement.body) {
                context.assign(temporary.makeReference(), context.generate(expression.tryBlock))
            }
            for (clausePsi in expression.catchClauses) {
                val catchVar = JsirVariable()
                val catch = JsirStatement.Catch(catchVar).apply {
                    statement.catchClauses += this
                }
                context.nestedBlock(catch.body) {
                    context.assign(temporary.makeReference(), context.generate(clausePsi.catchBody!!))
                }
            }
            val finallyPsi = expression.finallyBlock
            if (finallyPsi != null) {
                context.nestedBlock(statement.finallyClause) {
                    context.assign(temporary.makeReference(), context.generate(finallyPsi.finalExpression))
                }
            }
            context.append(statement)
        }

        return temporary.makeReference()
    }

    override fun visitSimpleNameExpression(expression: KtSimpleNameExpression, data: JsirContext?): JsirExpression {
        val resolvedCall = expression.getResolvedCall(context.bindingContext)!!
        return context.generateInvocation(null, resolvedCall)
    }

    override fun visitQualifiedExpression(expression: KtQualifiedExpression, data: JsirContext?): JsirExpression {
        val selector = expression.selectorExpression
        return when (selector) {
            is KtCallExpression -> {
                context.generateInvocation(expression.receiverExpression, selector.getResolvedCall(context.bindingContext)!!)
            }
            else -> {
                context.generateVariable(expression.receiverExpression, expression.getResolvedCall(context.bindingContext)!!).get()
            }
        }
    }
}
