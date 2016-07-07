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
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.scopes.receivers.ExpressionReceiver
import org.jetbrains.kotlin.resolve.scopes.receivers.ImplicitReceiver
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue

class VariableAccessorGenerator(private val context: JsirContext) {
    fun generate(receiverPsi: KtExpression, resolvedCall: ResolvedCall<*>): VariableAccessor {
        val descriptor = resolvedCall.resultingDescriptor
        if (descriptor is VariableDescriptor && descriptor.containingDeclaration is FunctionDescriptor) {
            return context.getVariable(descriptor)
        }

        val dispatchReceiver = resolvedCall.dispatchReceiver
        val extensionReceiver = resolvedCall.extensionReceiver

        val (receiverExpr, extensionExpr) = when {
            dispatchReceiver != null -> Pair(generateMainReceiver(receiverPsi, dispatchReceiver),
                                             extensionReceiver?.let { generateExtensionReceiver(it) })
            extensionReceiver != null -> Pair(null, generateMainReceiver(receiverPsi, extensionReceiver))
            else -> Pair(null, null)
        }

        val extensionArgs = extensionExpr?.let { listOf(it) }.orEmpty()

        return object : VariableAccessor {
            override fun get() = JsirExpression.Invocation(receiverExpr, descriptor, true, *extensionArgs.toTypedArray())

            override fun set(value: JsirExpression) {
                context.append(JsirExpression.Invocation(receiverExpr, descriptor, true, *(extensionArgs + value).toTypedArray()))
            }
        }
    }

    private fun generateMainReceiver(receiverPsi: KtExpression, receiver: ReceiverValue) = when (receiver) {
        is ExpressionReceiver -> context.translateMemoized(receiver.expression)
        else -> context.translateMemoized(receiverPsi)
    }

    private fun generateExtensionReceiver(receiver: ReceiverValue) = when (receiver) {
        is ImplicitReceiver -> context.memoize(ThisGenerator(context).generate(receiver.declarationDescriptor as ClassDescriptor))
        else -> JsirExpression.Null
    }
}
