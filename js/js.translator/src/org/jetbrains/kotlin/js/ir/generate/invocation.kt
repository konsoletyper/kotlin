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

import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.VariableDescriptor
import org.jetbrains.kotlin.descriptors.VariableDescriptorWithAccessors
import org.jetbrains.kotlin.js.ir.JsirExpression
import org.jetbrains.kotlin.js.ir.JsirField
import org.jetbrains.kotlin.psi.KtArrayAccessExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCall
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.resolve.calls.model.VariableAsFunctionResolvedCall
import org.jetbrains.kotlin.resolve.scopes.receivers.ExpressionReceiver
import org.jetbrains.kotlin.resolve.scopes.receivers.ImplicitReceiver
import org.jetbrains.kotlin.resolve.scopes.receivers.ReceiverValue

internal fun JsirContext.generateInvocation(resolvedCall: ResolvedCall<*>, receiverFactory: (() -> JsirExpression)?): JsirExpression {
    val translatedArguments = resolvedCall.valueArgumentsByIndex!!.map { argument ->
        argument.arguments.map { generate(it.getArgumentExpression()!!) }
    }
    return generateInvocation(resolvedCall, translatedArguments, receiverFactory)
}

internal fun JsirContext.generateInvocation(resolvedCall: ResolvedCall<*>, arguments: List<List<JsirExpression>>,
                                            receiverFactory: (() -> JsirExpression)?): JsirExpression {
    val descriptor = resolvedCall.resultingDescriptor
    if (descriptor is VariableDescriptor && descriptor.containingDeclaration == function) {
        return getVariable(descriptor).get()
    }

    val (receiverExpr, extensionExpr) = generateReceiver(resolvedCall, receiverFactory)
    val args = extensionExpr?.let { listOf(it) }.orEmpty() + generateArguments(resolvedCall, arguments)

    val functionDescriptor = when (descriptor) {
        is VariableDescriptorWithAccessors -> {
            descriptor.getter!!
        }
        else -> descriptor as FunctionDescriptor
    }

    return JsirExpression.Invocation(receiverExpr, functionDescriptor, true, *args.toTypedArray())
}

private fun JsirContext.generateArguments(resolvedCall: ResolvedCall<*>, arguments: List<List<JsirExpression>>): List<JsirExpression> {
    val function = resolvedCall.resultingDescriptor
    if (function?.valueParameters?.isEmpty() ?: false) return emptyList()

    return function.valueParameters.map { parameter ->
        val argument = arguments[parameter.index]
        if (parameter.varargElementType == null) {
            if (argument.isNotEmpty()) {
                memoize(argument[0])
            }
            else {
                JsirExpression.Undefined
            }
        }
        else {
            JsirExpression.ArrayOf(*argument.map { memoize(it) }.toTypedArray())
        }
    }
}

internal fun JsirContext.generateVariable(psi: KtExpression): VariableAccessor {
    val receiverFactory: (() -> JsirExpression)? = when (psi) {
        is KtQualifiedExpression -> ({ generate(psi.receiverExpression) })
        is KtArrayAccessExpression -> return generateArrayVariable(psi)
        else -> null
    }

    return generateVariable(psi.getResolvedCall(bindingContext)!!, receiverFactory)
}

internal fun JsirContext.generateVariable(resolvedCall: ResolvedCall<*>, receiverFactory: (() -> JsirExpression)?): VariableAccessor {
    val descriptor = resolvedCall.resultingDescriptor
    if (descriptor is VariableDescriptor && descriptor.containingDeclaration is FunctionDescriptor) {
        return getVariable(descriptor)
    }

    if (descriptor !is VariableDescriptorWithAccessors) error("Non-local variable should have accessors: $descriptor")

    val (receiverExpr, extensionExpr) = generateReceiver(resolvedCall, receiverFactory)
    val extensionArgs = extensionExpr?.let { listOf(it) }.orEmpty()

    return object : VariableAccessor {
        override fun get() = JsirExpression.Invocation(receiverExpr, descriptor.getter!!, true, *extensionArgs.toTypedArray())

        override fun set(value: JsirExpression) {
            append(JsirExpression.Invocation(receiverExpr, descriptor.setter!!, true, *(extensionArgs + value).toTypedArray()))
        }
    }
}

internal fun JsirContext.generateArrayVariable(arrayPsi: KtArrayAccessExpression): VariableAccessor {
    val getResolvedCall = bindingContext[BindingContext.INDEXED_LVALUE_GET, arrayPsi]
    val setResolvedCall = bindingContext[BindingContext.INDEXED_LVALUE_SET, arrayPsi]
    val array = memoize(arrayPsi.arrayExpression!!)
    val indexes = arrayPsi.indexExpressions.map { listOf(memoize(it)) }
    val arrayReceiverFactory: () -> JsirExpression = { array }

    return object: VariableAccessor {
        override fun get(): JsirExpression {
            return generateInvocation(getResolvedCall!!, indexes, arrayReceiverFactory)
        }

        override fun set(value: JsirExpression) {
            append(generateInvocation(setResolvedCall!!, indexes + listOf(listOf(value)), arrayReceiverFactory))
        }
    }
}

internal fun JsirContext.generateReceiver(
        resolvedCall: ResolvedCall<*>,
        receiverFactory: (() -> JsirExpression)?
): Pair<JsirExpression?, JsirExpression?> {
    val dispatchReceiver = resolvedCall.dispatchReceiver
    val extensionReceiver = resolvedCall.extensionReceiver

    if (resolvedCall is VariableAsFunctionResolvedCall) {
        return Pair(generateInvocation(resolvedCall.variableCall, receiverFactory), null)
    }

    return when {
        dispatchReceiver != null -> Pair(generateMainReceiver(dispatchReceiver, receiverFactory),
                                         extensionReceiver?.let { generateExtensionReceiver(it) })
        extensionReceiver != null -> Pair(null, generateMainReceiver(extensionReceiver, receiverFactory))
        else -> Pair(null, null)
    }
}

private fun JsirContext.generateMainReceiver(receiver: ReceiverValue, receiverFactory: (() -> JsirExpression)?) = when (receiver) {
    is ExpressionReceiver -> memoize(receiverFactory?.let { it() } ?: memoize(receiver.expression))
    else -> memoize(receiverFactory!!())
}

private fun JsirContext.generateExtensionReceiver(receiver: ReceiverValue) = when (receiver) {
    is ImplicitReceiver -> memoize(generateThis(receiver.declarationDescriptor as ClassDescriptor))
    else -> JsirExpression.Null
}

internal fun JsirContext.generateThis(descriptor: ClassDescriptor): JsirExpression {
    var result: JsirExpression = JsirExpression.This
    var currentClass = classDescriptor!!
    while (currentClass != descriptor) {
        result = JsirExpression.FieldAccess(result, JsirField.OuterClass(currentClass))
        currentClass = currentClass.containingDeclaration as ClassDescriptor
    }
    return result
}

internal fun JsirContext.defaultReceiverFactory(psi: KtExpression?): (() -> JsirExpression)? = psi?.let { ({ generate(it) }) }