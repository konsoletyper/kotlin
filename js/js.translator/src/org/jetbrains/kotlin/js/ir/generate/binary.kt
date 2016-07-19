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

import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.js.ir.*
import org.jetbrains.kotlin.js.translate.utils.BindingUtils
import org.jetbrains.kotlin.js.translate.utils.JsDescriptorUtils
import org.jetbrains.kotlin.js.translate.utils.PsiUtils
import org.jetbrains.kotlin.lexer.KtToken
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCall
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import org.jetbrains.kotlin.types.expressions.OperatorConventions

internal fun JsirContext.generateBinary(expression: KtBinaryExpression): JsirExpression {
    val operation = PsiUtils.getOperationToken(expression)
    val operationDescriptor = BindingUtils.getCallableDescriptorForOperationExpression(bindingContext, expression)
    val receiverFactory = defaultReceiverFactory(expression.left!!)
    return when {
        operation == KtTokens.ELVIS -> generateElvis(expression.left!!, expression.right!!)
        isAssignmentOperator(operation) -> {
            generateAssignment(expression)
            JsirExpression.Constant(null)
        }
        operationDescriptor != null && JsDescriptorUtils.isCompareTo(operationDescriptor) -> generateCompareTo(expression)
        operation == KtTokens.EXCLEQ -> generateInvocation(expression.getResolvedCall(bindingContext)!!, receiverFactory).negate()
        operation == KtTokens.ANDAND -> generateLogical(expression, false)
        operation == KtTokens.OROR -> generateLogical(expression, true)
        operation == KtTokens.IN_KEYWORD -> generateIn(expression)
        operation == KtTokens.NOT_IN -> generateIn(expression).negate()
        operation == KtTokens.EQEQEQ -> JsirExpression.Binary(JsirBinaryOperation.REF_EQ, JsirType.ANY, generate (expression.left!!),
                                                              generate(expression.right!!))
        operation == KtTokens.EXCLEQEQEQ -> JsirExpression.Binary(JsirBinaryOperation.REF_NE, JsirType.ANY, generate (expression.left!!),
                                                                  generate(expression.right!!))
        else -> generateInvocation(expression.getResolvedCall(bindingContext)!!, receiverFactory)
    }
}

private fun JsirContext.generateIn(expression: KtBinaryExpression): JsirExpression {
    val receiverFactory = defaultReceiverFactory(expression.right!!)
    val argumentsFactory = { listOf(listOf(generate(expression.left!!))) }
    return generateInvocation(expression.getResolvedCall(bindingContext)!!, argumentsFactory, receiverFactory)
}

private fun JsirContext.generateElvis(leftPsi: KtExpression, rightPsi: KtExpression): JsirExpression {
    val result = JsirVariable().makeReference()
    assign(result, generate(leftPsi))
    val conditional = JsirStatement.If(result.nullCheck())
    nestedBlock(conditional.thenBody) {
        assign(result, generate(rightPsi))
    }
    append(conditional)

    return result
}

private fun JsirContext.generateAssignment(psi: KtBinaryExpression) {
    val leftPsi = psi.left!!
    val rightPsi = psi.right!!
    val call = psi.getResolvedCall(bindingContext) ?: return generateSimpleAssignment(psi)

    if (BindingUtils.isVariableReassignment(bindingContext, psi)) {
        val variable = generateVariable(leftPsi)
        variable.set(JsirExpression.Invocation(variable.get(), call.resultingDescriptor as FunctionDescriptor, true, generate(rightPsi)))
    }
    else {
        val left = memoize(leftPsi)
        append(JsirExpression.Invocation(left, call.resultingDescriptor as FunctionDescriptor, true, generate(rightPsi)))
    }
}

private fun JsirContext.generateSimpleAssignment(psi: KtBinaryExpression) {
    val leftPsi = psi.left!!
    val left = generateVariable(leftPsi)
    val right = generate(psi.right!!)
    val psiOperation = PsiUtils.getOperationToken(psi)

    when (psiOperation) {
        KtTokens.EQ -> {
            left.set(right)
            return
        }
        else -> error("Unexpected operation $psiOperation")
    }
}

private fun isAssignmentOperator(operationToken: KtToken) =
        OperatorConventions.ASSIGNMENT_OPERATIONS.keys.contains(operationToken) ||
        PsiUtils.isAssignment(operationToken)

private fun JsirContext.generateCompareTo(expression: KtBinaryExpression): JsirExpression {
    val psiOperation = PsiUtils.getOperationToken(expression)
    val call = expression.getResolvedCall(bindingContext)!!
    val function = call.resultingDescriptor as FunctionDescriptor

    val left = memoize(expression.left!!)
    val right = generate(expression.right!!)

    val operation = when (psiOperation) {
        KtTokens.EQEQ,
        KtTokens.EQEQEQ -> JsirBinaryOperation.EQ
        KtTokens.EXCLEQ,
        KtTokens.EXCLEQEQEQ -> JsirBinaryOperation.NE
        KtTokens.LT -> JsirBinaryOperation.LT
        KtTokens.LTEQ -> JsirBinaryOperation.LOE
        KtTokens.GT -> JsirBinaryOperation.GT
        KtTokens.GTEQ -> JsirBinaryOperation.GOE
        else -> throw IllegalArgumentException("Unexpected comparison token: ${psiOperation}")
    }

    val invocation = generateInvocation(call, { listOf(listOf(right)) }, { left })
    val type = getPrimitiveType(function.receiverClass!!.fqNameSafe.asString())

    return JsirExpression.Binary(operation, type, invocation, JsirExpression.Constant(0))
}

private fun JsirContext.generateLogical(expression: KtBinaryExpression, invert: Boolean): JsirExpression {
    val left = expression.left!!
    val right = expression.right!!

    val temporary = JsirVariable().makeReference()
    assign(temporary, generate(left))
    val condition = JsirStatement.If(if (invert) temporary.negate() else temporary)
    withSource(expression) {
        nestedBlock(condition.thenBody) {
            assign(temporary, generate(right))
        }
    }
    append(condition)

    return temporary
}