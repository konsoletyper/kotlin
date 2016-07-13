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
import org.jetbrains.kotlin.js.ir.JsirBinaryOperation
import org.jetbrains.kotlin.js.ir.JsirExpression
import org.jetbrains.kotlin.js.ir.getPrimitiveType
import org.jetbrains.kotlin.js.ir.receiverClass
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
    return when {
        operation == KtTokens.ELVIS -> translateElvis(expression.left!!, expression.right!!)
        isAssignmentOperator(operation) -> {
            translateAssignment(expression)
            JsirExpression.Null
        }
        operationDescriptor != null && JsDescriptorUtils.isCompareTo(operationDescriptor) -> generateCompareTo(expression)
        operation == KtTokens.EXCLEQ -> generateInvocation(expression.left!!, expression.getResolvedCall(bindingContext)!!).negate()
        else -> generateInvocation(expression.left!!, expression.getResolvedCall(bindingContext)!!)
    }
}

private fun JsirContext.translateElvis(leftPsi: KtExpression, rightPsi: KtExpression): JsirExpression {
    val left = memoize(leftPsi)
    return JsirExpression.Conditional(left.nullCheck(), left, generate(rightPsi))
}

private fun JsirContext.translateAssignment(psi: KtBinaryExpression) {
    val call = psi.getResolvedCall(bindingContext) ?: return translateSimpleAssignment(psi)
    val leftPsi = psi.left!!
    val rightPsi = psi.right!!

    if (BindingUtils.isVariableReassignment(bindingContext, psi)) {
        val variable = generateVariable(leftPsi)
        variable.set(JsirExpression.Invocation(variable.get(), call.resultingDescriptor as FunctionDescriptor, true, generate(rightPsi)))
    }
    else {
        val left = memoize(leftPsi)
        JsirExpression.Invocation(left, call.resultingDescriptor as FunctionDescriptor, true, generate(rightPsi))
    }
}

private fun JsirContext.translateSimpleAssignment(psi: KtBinaryExpression) {
    val left = generateVariable(psi.left!!)
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

    val invocation = generateInvocation(expression.left, call)
    val type = getPrimitiveType(function.receiverClass!!.fqNameSafe.asString())

    return JsirExpression.Binary(operation, type, invocation, JsirExpression.Constant(0))
}