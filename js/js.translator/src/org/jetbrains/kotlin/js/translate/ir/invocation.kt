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

import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.VariableDescriptor
import org.jetbrains.kotlin.js.ir.JsirExpression
import org.jetbrains.kotlin.js.ir.JsirField
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.scopes.receivers.ExpressionReceiver
import org.jetbrains.kotlin.resolve.scopes.receivers.ImplicitReceiver
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue

fun JsirContext.generateInvocation(receiverPsi: KtExpression?, resolvedCall: ResolvedCall<*>): JsirExpression {
    val (receiverExpr, extensionExpr) = generateReceiver(receiverPsi, resolvedCall)
    val args = extensionExpr?.let { listOf(it) }.orEmpty() + generateArguments(resolvedCall)

    return JsirExpression.Invocation(receiverExpr, resolvedCall.resultingDescriptor, true, *args.toTypedArray())
}

private fun JsirContext.generateArguments(resolvedCall: ResolvedCall<*>): List<JsirExpression> {
    if (function.valueParameters.isEmpty()) return emptyList()

    val function = resolvedCall.resultingDescriptor
    val arguments = resolvedCall.valueArgumentsByIndex!!
    return function.valueParameters.map { parameter ->
        val argument = arguments[parameter.index]
        if (parameter.varargElementType != null) {
            memoize(argument.arguments[0].getArgumentExpression()!!)
        }
        else {
            JsirExpression.ArrayOf(*argument.arguments.map { memoize(it.getArgumentExpression()!!) }.toTypedArray())
        }
    }
}

fun JsirContext.generateVariable(receiverPsi: KtExpression, resolvedCall: ResolvedCall<*>): VariableAccessor {
    val descriptor = resolvedCall.resultingDescriptor
    if (descriptor is VariableDescriptor && descriptor.containingDeclaration is FunctionDescriptor) {
        return getVariable(descriptor)
    }

    val (receiverExpr, extensionExpr) = generateReceiver(receiverPsi, resolvedCall)
    val extensionArgs = extensionExpr?.let { listOf(it) }.orEmpty()

    return object : VariableAccessor {
        override fun get() = JsirExpression.Invocation(receiverExpr, descriptor, true, *extensionArgs.toTypedArray())

        override fun set(value: JsirExpression) {
            append(JsirExpression.Invocation(receiverExpr, descriptor, true, *(extensionArgs + value).toTypedArray()))
        }
    }
}

fun JsirContext.generateReceiver(receiverPsi: KtExpression?, resolvedCall: ResolvedCall<*>): Pair<JsirExpression?, JsirExpression?> {
    val dispatchReceiver = resolvedCall.dispatchReceiver
    val extensionReceiver = resolvedCall.extensionReceiver

    return when {
        dispatchReceiver != null -> Pair(generateMainReceiver(receiverPsi, dispatchReceiver),
                                         extensionReceiver?.let { generateExtensionReceiver(it) })
        extensionReceiver != null -> Pair(null, generateMainReceiver(receiverPsi, extensionReceiver))
        else -> Pair(null, null)
    }
}

private fun JsirContext.generateMainReceiver(receiverPsi: KtExpression?, receiver: ReceiverValue) = when (receiver) {
    is ExpressionReceiver -> memoize(receiver.expression)
    else -> memoize(receiverPsi!!)
}

private fun JsirContext.generateExtensionReceiver(receiver: ReceiverValue) = when (receiver) {
    is ImplicitReceiver -> memoize(generateThis(receiver.declarationDescriptor as ClassDescriptor))
    else -> JsirExpression.Null
}

fun JsirContext.generateThis(descriptor: ClassDescriptor): JsirExpression {
    var result: JsirExpression = JsirExpression.This
    var currentClass = classDescriptor!!
    while (currentClass != descriptor) {
        result = JsirExpression.FieldAccess(result, JsirField.OuterClass(currentClass))
        currentClass = currentClass.containingDeclaration as ClassDescriptor
    }
    return result
}