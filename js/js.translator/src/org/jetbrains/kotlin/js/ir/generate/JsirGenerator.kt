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

package org.jetbrains.kotlin.js.ir.generate

import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.js.ir.*
import org.jetbrains.kotlin.js.resolve.diagnostics.ErrorsJs
import org.jetbrains.kotlin.js.translate.utils.BindingUtils
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getTextWithLocation
import org.jetbrains.kotlin.psi.psiUtil.isExtensionDeclaration
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.BindingContextUtils
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCall
import org.jetbrains.kotlin.resolve.constants.NullValue
import org.jetbrains.kotlin.resolve.constants.evaluate.ConstantExpressionEvaluator
import org.jetbrains.kotlin.resolve.descriptorUtil.getSuperClassNotAny
import org.jetbrains.kotlin.types.TypeUtils
import org.jetbrains.kotlin.types.expressions.ExpressionTypingUtils.isFunctionExpression
import org.jetbrains.kotlin.types.expressions.ExpressionTypingUtils.isFunctionLiteral
import org.jetbrains.kotlin.utils.singletonOrEmptyList

class JsirGenerator(private val bindingTrace: BindingTrace, module: ModuleDescriptor) : KtVisitor<JsirExpression, JsirContext>() {
    val context: JsirContext = JsirContext(bindingTrace, module) { expr ->
        val visitor = this
        expr.accept(this, visitor.context)
    }

    override fun visitKtElement(expression: KtElement, context: JsirContext): JsirExpression {
        bindingTrace.report(ErrorsJs.NOT_SUPPORTED.on(expression, expression))
        return JsirExpression.Undefined
    }

    override fun visitConstantExpression(expression: KtConstantExpression, context: JsirContext): JsirExpression {
        return generateConstantExpression(expression, context).apply { source = expression }
    }

    private fun generateConstantExpression(expression: KtConstantExpression, context: JsirContext): JsirExpression {
        val compileTimeValue = ConstantExpressionEvaluator.getConstant(expression, context.bindingContext) ?:
                               error("Expression is not compile time value: " + expression.getTextWithLocation() + " ")
        val expectedType = context.bindingContext.getType(expression)
        val constant = compileTimeValue.toConstantValue(expectedType ?: TypeUtils.NO_EXPECTED_TYPE)
        if (constant is NullValue) return JsirExpression.Constant(null)

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
            templateGenerator.flush()
            JsirExpression.Concat(*templateGenerator.parts.toTypedArray())
        }
    }

    override fun visitBlockExpression(expression: KtBlockExpression, data: JsirContext) = generateBlockExpression(expression, null)

    private fun generateBlockExpression(expression: KtBlockExpression, label: String?): JsirExpression {
        return if (expression.statements.isNotEmpty()) {
            if (label != null) {
                val block = JsirStatement.Block()
                val temporary = JsirVariable().makeReference()
                context.nestedLabel(label, block, false, false) {
                    for (statement in expression.statements.dropLast(1)) {
                        context.append(context.generate(statement))
                    }
                    context.assign(temporary, context.generate(expression.statements.last()))
                }
                temporary
            }
            else {
                for (statement in expression.statements.dropLast(1)) {
                    context.append(context.generate(statement))
                }
                context.generate(expression.statements.last())
            }
        }
        else {
            JsirExpression.Undefined
        }
    }

    override fun visitReturnExpression(expression: KtReturnExpression, data: JsirContext): JsirExpression {
        context.withSource(expression) {
            val returnValue = expression.returnedExpression?.accept(this, data)
            val target = getNonLocalReturnTarget(expression) ?: context.function
            context.append(JsirStatement.Return(returnValue, target!!))
        }
        return JsirExpression.Undefined
    }

    override fun visitBreakExpression(expression: KtBreakExpression, data: JsirContext?): JsirExpression {
        context.withSource(expression) {
            val target = expression.getTargetLabel()?.let { context.getLabelTarget(it) } ?: context.defaultBreakTarget
            context.append(JsirStatement.Break(target!!))
        }
        return JsirExpression.Undefined
    }

    override fun visitContinueExpression(expression: KtContinueExpression, data: JsirContext?): JsirExpression {
        context.withSource(expression) {
            val target = expression.getTargetLabel()?.let { context.getLabelTarget(it) } ?: context.defaultContinueTarget!!
            val replacement = context.continueReplacements[target]
            context.append(if (replacement != null) {
                JsirStatement.Break(replacement)
            }
            else {
                JsirStatement.Continue(target)
            })
        }
        return JsirExpression.Undefined
    }

    override fun visitLabeledExpression(expression: KtLabeledExpression, data: JsirContext?): JsirExpression {
        val label = expression.getLabelName()!!
        val body = expression.baseExpression!!
        return when (body) {
            is KtDoWhileExpression -> generateDoWhile(body, label)
            is KtWhileExpression -> generateWhile(body, label)
            is KtForExpression -> context.generateFor(body, label)
            is KtBlockExpression -> generateBlockExpression(body, label)
            else -> context.generate(body)
        }
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
            JsirExpression.Undefined
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
        return generateFunctionDeclaration(functionPsi, context.declaration !is ClassDescriptor)
    }

    override fun visitKtFile(file: KtFile, data: JsirContext): JsirExpression {
        context.nestedFile(JsirFile(context.pool, file.name)) {
            for (declaration in file.declarations) {
                context.generate(declaration)
            }
        }

        return JsirExpression.Undefined
    }

    override fun visitClassOrObject(psi: KtClassOrObject, data: JsirContext): JsirExpression {
        val descriptor = BindingUtils.getDescriptorForElement(context.bindingContext, psi) as ClassDescriptor
        generateClass(psi, descriptor)
        return JsirExpression.Undefined
    }

    override fun visitObjectLiteralExpression(psi: KtObjectLiteralExpression, data: JsirContext?): JsirExpression {
        val descriptor = BindingUtils.getDescriptorForElement(context.bindingContext, psi.objectDeclaration) as ClassDescriptor
        generateClass(psi.objectDeclaration, descriptor)
        val constructor = context.bindingContext[BindingContext.CONSTRUCTOR, psi.objectDeclaration]!!
        return JsirExpression.NewInstance(constructor)
    }

    private fun generateClass(psi: KtClassOrObject, descriptor: ClassDescriptor) {
        val cls = JsirClass(descriptor, context.file!!)
        val outerParameter = if (descriptor.isInner) JsirVariable("\$outer") else null
        val delegationGenerator = DelegationGenerator(context, psi, cls)
        context.nestedClass(cls, outerParameter) {
            if (outerParameter != null && !(descriptor.getSuperClassNotAny()?.isInner ?: false)) {
                cls.hasOuterProperty = true
            }

            context.nestedVariableScope {
                val primaryConstructorPsi = psi.getPrimaryConstructor()
                val constructorDescriptor = context.bindingContext[BindingContext.CONSTRUCTOR, psi] ?:
                                            context.bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, primaryConstructorPsi] as?
                                            ConstructorDescriptor

                if (constructorDescriptor != null) {
                    for (parameterDescriptor in constructorDescriptor.valueParameters) {
                        JsirParameter(context.getVariable(parameterDescriptor).localVariable)
                    }
                }

                if (primaryConstructorPsi != null) {
                    context.nestedBlock(cls.initializerBody) {
                        delegationGenerator.contributeToInitializer()
                    }
                    primaryConstructorPsi.accept(this, context)
                }
                else if (constructorDescriptor != null) {
                    context.nestedBlock(cls.initializerBody) {
                        synthesizeSuperCall(constructorDescriptor)
                        delegationGenerator.contributeToInitializer()
                    }
                }

                val bodyPsi = psi.getBody()
                if (bodyPsi != null) {
                    for (declaration in bodyPsi.declarations) {
                        declaration.accept(this, context)
                    }
                }
                delegationGenerator.generateMembers()
            }
        }
    }

    override fun visitCallExpression(expression: KtCallExpression, data: JsirContext?): JsirExpression {
        val resolvedCall = expression.getResolvedCall(context.bindingContext)!!
        val callee = expression.calleeExpression!!
        val qualifier = if (callee is KtDotQualifiedExpression) {
            callee.receiverExpression
        }
        else {
            callee
        }

        return context.generateInvocation(resolvedCall, context.defaultReceiverFactory(qualifier))
    }

    override fun visitBinaryWithTypeRHSExpression(expression: KtBinaryExpressionWithTypeRHS, data: JsirContext?): JsirExpression {
        return when (expression.operationReference.getReferencedNameElementType()) {
            KtTokens.AS_KEYWORD,
            KtTokens.AS_SAFE -> generateBinaryWithTypeBase(expression.left, expression.right!!)
            else -> super.visitBinaryWithTypeRHSExpression(expression, data)
        }
    }

    private fun generateBinaryWithTypeBase(left: KtExpression, right: KtTypeReference): JsirExpression {
        val sourceType = BindingUtils.getTypeForExpression(context.bindingContext, left)
        val type = BindingUtils.getTypeByReference(context.bindingContext, right)
        return context.generateCast(context.generate(left), sourceType, type)
    }

    override fun visitIsExpression(expression: KtIsExpression, data: JsirContext?): JsirExpression {
        val sourceType = BindingUtils.getTypeForExpression(context.bindingContext, expression.leftHandSide)
        val type = BindingUtils.getTypeByReference(context.bindingContext, expression.typeReference!!)
        val result = context.generateInstanceOf(context.generate(expression.leftHandSide), sourceType, type)
        return if (expression.isNegated) result.negate() else result
    }

    override fun visitProperty(propertyPsi: KtProperty, data: JsirContext): JsirExpression {
        val descriptor = context.bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, propertyPsi]
        if (context.declaration is FunctionDescriptor) {
            val variable = descriptor as VariableDescriptor
            val initializer = propertyPsi.initializer
            if (initializer != null) {
                context.getVariable(variable).set(context.generate(initializer))
            }
        }
        else {
            val variable = descriptor as VariableDescriptorWithAccessors
            JsirProperty(variable, context.container!!)
            val initializer = propertyPsi.initializer
            if (initializer != null) {
                context.nestedBlock(context.container!!.initializerBody) {
                    val receiver = if (variable.containingDeclaration is ClassDescriptor) JsirExpression.This else null
                    val access = JsirExpression.FieldAccess(receiver, JsirField.Backing(variable))
                    context.assign(access, context.generate(initializer))
                }
            }

            generateAccessor(propertyPsi.getter, variable.getter)
            generateAccessor(propertyPsi.setter, variable.setter)
        }
        return JsirExpression.Undefined
    }

    private fun generateAccessor(psi: KtPropertyAccessor?, accessor: VariableAccessorDescriptor?) {
        if (accessor == null) return

        if (psi != null) {
            context.generate(psi)
        }
        else {
            val function = JsirFunction(accessor, context.container!!, false)
            val isGetter = accessor.valueParameters.isEmpty()
            val receiver = if (accessor.correspondingVariable.containingDeclaration is ClassDescriptor) {
                JsirExpression.This
            }
            else {
                null
            }
            val access = JsirExpression.FieldAccess(receiver, JsirField.Backing(accessor.correspondingVariable))
            if (isGetter) {
                function.body += JsirStatement.Return(access, accessor)
            }
            else {
                val parameter = JsirVariable(accessor.correspondingVariable.name.asString())
                function.parameters += JsirParameter(parameter)
                function.body += JsirStatement.Assignment(access, parameter.makeReference())
            }
        }
    }

    override fun visitIfExpression(expression: KtIfExpression, data: JsirContext): JsirExpression {
        val temporary = JsirVariable()

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

        return temporary.makeReference()
    }

    override fun visitWhenExpression(expression: KtWhenExpression, data: JsirContext?) = context.generateWhen(expression)

    override fun visitWhileExpression(expression: KtWhileExpression, data: JsirContext?) = generateWhile(expression, null)

    private fun generateWhile(expression: KtWhileExpression, label: String?): JsirExpression {
        val statement = JsirStatement.While(JsirExpression.Constant(true))
        context.nestedLabel(label, statement, true, true) {
            context.nestedBlock(statement.body) {
                context.withSource(expression.condition) {
                    val conditionalBreak = JsirStatement.If(context.generate(expression.condition!!).negate())
                    context.append(conditionalBreak)
                    context.nestedBlock(conditionalBreak.thenBody) {
                        context.append(JsirStatement.Break(statement))
                    }
                }

                expression.body?.let { context.generate(it) }
            }
        }
        context.append(statement)

        return JsirExpression.Undefined
    }

    override fun visitDoWhileExpression(expression: KtDoWhileExpression, data: JsirContext) = generateDoWhile(expression, null)

    private fun generateDoWhile(expression: KtDoWhileExpression, label: String?): JsirExpression {
        val statement = JsirStatement.DoWhile(JsirExpression.Constant(true))
        context.nestedLabel(label, statement, true, true) {
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
        context.append(statement)

        return JsirExpression.Undefined
    }

    override fun visitForExpression(expression: KtForExpression, data: JsirContext?): JsirExpression {
        return context.generateFor(expression, null)
    }

    override fun visitTryExpression(expression: KtTryExpression, data: JsirContext?): JsirExpression {
        val temporary = JsirVariable().makeReference()
        val statement = JsirStatement.Try()
        context.nestedBlock(statement.body) {
            context.assign(temporary, context.generate(expression.tryBlock))
        }
        for (clausePsi in expression.catchClauses) {
            val parameterDescriptor = context.bindingContext[BindingContext.VALUE_PARAMETER, clausePsi.catchParameter!!]!!
            val catchVar = context.getVariable(parameterDescriptor).localVariable
            val exceptionType = parameterDescriptor.type.constructor.declarationDescriptor as? ClassDescriptor
            val catch = JsirStatement.Catch(catchVar, exceptionType).apply {
                statement.catchClauses += this
            }
            context.nestedBlock(catch.body) {
                context.assign(temporary, context.generate(clausePsi.catchBody!!))
            }
        }
        val finallyPsi = expression.finallyBlock
        if (finallyPsi != null) {
            context.nestedBlock(statement.finallyClause) {
                context.generate(finallyPsi.finalExpression)
            }
        }

        if (statement.catchClauses.isNotEmpty() || statement.finallyClause.isNotEmpty()) {
            context.append(statement)
        }
        else {
            context.appendAll(statement.body)
        }

        return temporary
    }

    override fun visitSimpleNameExpression(expression: KtSimpleNameExpression, data: JsirContext?): JsirExpression {
        val resolvedCall = expression.getResolvedCall(context.bindingContext)
        if (resolvedCall != null) {
            return context.generateInvocation(resolvedCall, null)
        }
        else {
            val descriptor = context.bindingContext[BindingContext.REFERENCE_TARGET, expression] as ClassDescriptor
            assert(descriptor.kind == ClassKind.OBJECT) { "Expected object, encountered ${descriptor.kind}" }
            return JsirExpression.ObjectReference(descriptor)
        }
    }

    override fun visitThisExpression(expression: KtThisExpression, data: JsirContext?): JsirExpression {
        val resolvedCall = expression.getResolvedCall(context.bindingContext) ?: return JsirExpression.This

        val descriptor = (resolvedCall.resultingDescriptor as ReceiverParameterDescriptor).containingDeclaration
        return when (descriptor) {
            is ClassDescriptor -> context.generateThis(descriptor)
            is FunctionDescriptor -> context.extensionParameters[descriptor]!!.makeReference()
            else -> super.visitThisExpression(expression, data)
        }
    }

    override fun visitThrowExpression(expression: KtThrowExpression, data: JsirContext?): JsirExpression {
        context.append(JsirStatement.Throw(context.memoize(expression.thrownExpression!!)))
        return JsirExpression.Undefined
    }

    override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression, data: JsirContext?): JsirExpression {
        return generateQualified(expression, context.defaultReceiverFactory(expression.receiverExpression))
    }

    override fun visitSafeQualifiedExpression(expression: KtSafeQualifiedExpression, data: JsirContext?): JsirExpression {
        val receiver = context.memoize(expression.receiverExpression)
        val result = JsirVariable().makeReference()
        val conditional = JsirStatement.If(receiver.nullCheck().negate())
        context.nestedBlock(conditional.thenBody) {
            context.assign(result, generateQualified(expression) { receiver })
        }
        context.nestedBlock(conditional.elseBody) {
            context.assign(result, JsirExpression.Constant(null))
        }
        context.append(conditional)
        return result
    }

    private fun generateQualified(expression: KtQualifiedExpression, receiverFactory: (() -> JsirExpression)?): JsirExpression {
        val selector = expression.selectorExpression

        return when (selector) {
            is KtCallExpression -> {
                context.generateInvocation(selector.getResolvedCall(context.bindingContext)!!, receiverFactory)
            }
            else -> {
                context.generateVariable(expression.getResolvedCall(context.bindingContext)!!, receiverFactory).get()
            }
        }
    }

    override fun visitArrayAccessExpression(expression: KtArrayAccessExpression, data: JsirContext?): JsirExpression {
        return context.withSource(expression) { context.generateArrayVariable(expression).get() }
    }

    override fun visitPropertyAccessor(accessor: KtPropertyAccessor, data: JsirContext?): JsirExpression {
        return generateFunctionDeclaration(accessor, false)
    }

    override fun visitLambdaExpression(expression: KtLambdaExpression, data: JsirContext?): JsirExpression {
        return generateFunctionDeclaration(expression.functionLiteral, true)
    }

    override fun visitPrimaryConstructor(constructor: KtPrimaryConstructor, data: JsirContext?): JsirExpression {
        return generateFunctionDeclaration(constructor, false)
    }

    override fun visitAnonymousInitializer(initializer: KtAnonymousInitializer, data: JsirContext?): JsirExpression {
        context.nestedBlock(context.container!!.initializerBody) {
            val body = initializer.body
            if (body != null) {
                context.append(context.generate(body))
            }
        }
        return JsirExpression.Undefined
    }

    private fun generateFunctionDeclaration(functionPsi: KtDeclarationWithBody, static: Boolean): JsirExpression {
        val descriptor = context.bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, functionPsi] as FunctionDescriptor
        val function = JsirFunction(descriptor, context.container!!, static)

        val extensionParameter = if (functionPsi.isExtensionDeclaration()) JsirVariable("\$receiver") else null
        if (extensionParameter != null) {
            function.parameters += JsirParameter(extensionParameter)
        }

        for (parameterPsi in functionPsi.valueParameters) {
            val parameter = BindingUtils.getDescriptorForElement(context.bindingContext, parameterPsi) as ValueParameterDescriptor
            val property = context.bindingContext[BindingContext.VALUE_PARAMETER_AS_PROPERTY, parameter]
            if (property != null) {
                JsirProperty(property, context.container!!)
                generateAccessor(null, property.getter)
                generateAccessor(null, property.setter)
                context.nestedBlock(context.container!!.initializerBody) {
                    val lhs = JsirExpression.FieldAccess(JsirExpression.This, JsirField.Backing(property))
                    context.assign(lhs, context.getVariable(parameter).get())
                }
            }
        }

        context.nestedFunction(descriptor, extensionParameter) {
            val outerParameter = context.outerParameter
            if (descriptor is ConstructorDescriptor && outerParameter != null) {
                function.parameters += JsirParameter(outerParameter)
                val cls = descriptor.containingDeclaration
                if (descriptor.isPrimary && !(cls.getSuperClassNotAny()?.isInner ?: false)) {
                    context.nestedBlock(function.body) {
                        val fieldRef = JsirExpression.FieldAccess(
                                JsirExpression.This,
                                JsirField.OuterClass(descriptor.containingDeclaration))
                        context.assign(fieldRef, outerParameter.makeReference())
                    }
                }
            }
            for (parameterDescriptor in descriptor.valueParameters) {
                val parameter = JsirParameter(context.getVariable(parameterDescriptor).localVariable)
                val parameterPsi = functionPsi.valueParameters.getOrNull(parameterDescriptor.index)
                val defaultValuePsi = parameterPsi?.defaultValue
                if (defaultValuePsi != null) {
                    context.nestedBlock(parameter.defaultBody) {
                        context.assign(parameter.variable.makeReference(), context.generate(defaultValuePsi))
                    }
                }
                function.parameters += parameter
            }

            context.nestedBlock(function.body) {
                if (descriptor is ConstructorDescriptor) {
                    synthesizeSuperCall(descriptor)
                    function.body += context.container!!.initializerBody
                    context.container!!.initializerBody.clear()
                }

                val returnValue = functionPsi.bodyExpression?.let { context.generate(it) } ?: JsirExpression.Undefined

                if (descriptor !is ConstructorDescriptor) {
                    context.append(JsirStatement.Return(returnValue, descriptor))
                }
            }
        }

        return JsirExpression.FunctionReference(descriptor)
    }

    private fun synthesizeSuperCall(descriptor: ConstructorDescriptor) {
        val delegatedCall = context.bindingContext[BindingContext.CONSTRUCTOR_RESOLVED_DELEGATION_CALL, descriptor]
        if (delegatedCall != null) {
            val delegatedConstructor = delegatedCall.resultingDescriptor
            val receiver = JsirExpression.This
            val arguments = context.outerParameter?.makeReference().singletonOrEmptyList() +
                            context.generateArguments(delegatedCall, context.generateRawArguments(delegatedCall))
            val invocation = JsirExpression.Invocation(receiver, delegatedConstructor, false, *arguments.toTypedArray())

            context.append(invocation)
        }
    }
}

