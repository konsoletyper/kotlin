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
import org.jetbrains.kotlin.js.translate.utils.BindingUtils
import org.jetbrains.kotlin.psi.KtDeclarationWithBody
import org.jetbrains.kotlin.psi.KtPropertyAccessor
import org.jetbrains.kotlin.psi.psiUtil.isExtensionDeclaration
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.descriptorUtil.getSuperClassNotAny
import org.jetbrains.kotlin.utils.singletonOrEmptyList

internal fun JsirContext.generateFunctionDeclaration(functionPsi: KtDeclarationWithBody, static: Boolean): JsirExpression {
    val descriptor = bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, functionPsi] as FunctionDescriptor
    val function = JsirFunction(descriptor, container!!, static)
    return generateFunctionDeclaration(functionPsi, function)
}

internal fun JsirContext.generateFunctionDeclaration(functionPsi: KtDeclarationWithBody, function: JsirFunction): JsirExpression {
    val descriptor = bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, functionPsi] as FunctionDescriptor

    val extensionParameter = if (functionPsi.isExtensionDeclaration()) {
        function.variableContainer.createVariable(false, "\$receiver")
    }
    else {
        null
    }
    if (extensionParameter != null) {
        function.parameters += JsirParameter(extensionParameter)
    }

    nestedFunction(function, extensionParameter, null) {
        generateParameters(functionPsi, function)

        nestedBlock(function.body) {
            val returnValue = functionPsi.bodyExpression?.let { generate(it) } ?: JsirExpression.Undefined()

            if (descriptor !is ConstructorDescriptor) {
                append(JsirStatement.Return(returnValue, descriptor))
            }
        }
    }

    return JsirExpression.FunctionReference(descriptor)
}

internal fun JsirContext.generateConstructorDeclaration(
        functionPsi: KtDeclarationWithBody?,
        function: JsirFunction,
        afterSuperCall: () -> Unit
) {
    val descriptor = function.descriptor as ConstructorDescriptor

    if (functionPsi != null) {
        for (parameterPsi in functionPsi.valueParameters) {
            val parameter = BindingUtils.getDescriptorForElement(bindingContext, parameterPsi) as ValueParameterDescriptor
            val property = bindingContext[BindingContext.VALUE_PARAMETER_AS_PROPERTY, parameter]
            if (property != null) {
                JsirProperty(property, container!!)
                generateAccessor(null, property.getter)
                generateAccessor(null, property.setter)
            }
        }
    }

    val declaringClass = function.container as JsirClass
    val outer = declaringClass.outer
    val (outerParameter, needsOuterField) = if (outer != null) {
        val parameter = function.variableContainer.createVariable(false, "\$outer")
        val parentDescriptor = outer.descriptor.getSuperClassNotAny()
        val parent = parentDescriptor?.let { module.classes[it] }
        Pair(parameter, parent == null || parent.outer == null)
    }
    else {
        Pair(null, false)
    }

    nestedFunction(function, null, outerParameter) {
        nestedBlock(function.body) {
            if (outerParameter != null) {
                function.parameters += JsirParameter(outerParameter)
                if (descriptor.isPrimary && needsOuterField) {
                    nestedBlock(function.body) {
                        val fieldRef = JsirExpression.FieldAccess(
                                JsirExpression.This(),
                                JsirField.OuterClass(descriptor.containingDeclaration))
                        assign(fieldRef, outerParameter.makeReference())
                    }
                }
            }
            if (functionPsi != null) {
                for (parameterPsi in functionPsi.valueParameters) {
                    val parameter = BindingUtils.getDescriptorForElement(bindingContext, parameterPsi) as ValueParameterDescriptor
                    val property = bindingContext[BindingContext.VALUE_PARAMETER_AS_PROPERTY, parameter]
                    if (property != null) {
                        val fieldRef = JsirExpression.FieldAccess(JsirExpression.This(), JsirField.Backing(property))
                        assign(fieldRef, getVariable(parameter).get())
                    }
                }
            }
        }
        generateParameters(functionPsi, function)
        nestedBlock(function.body) {
            synthesizeSuperCall(descriptor)
            afterSuperCall()
            functionPsi?.bodyExpression?.let { generate(it) }
        }
    }
}

private fun JsirContext.generateParameters(functionPsi: KtDeclarationWithBody?, function: JsirFunction) {
    val descriptor = function.descriptor
    for (parameterDescriptor in descriptor.valueParameters) {
        val parameter = JsirParameter(getVariable(parameterDescriptor).localVariable)
        val parameterPsi = functionPsi?.valueParameters?.getOrNull(parameterDescriptor.index)
        val defaultValuePsi = parameterPsi?.defaultValue
        if (defaultValuePsi != null) {
            nestedBlock(parameter.defaultBody) {
                assign(parameter.variable.makeReference(), generate(defaultValuePsi))
            }
        }
        function.parameters += parameter
    }
}

internal fun JsirContext.synthesizeSuperCall(descriptor: ConstructorDescriptor) {
    val delegatedCall = bindingContext[BindingContext.CONSTRUCTOR_RESOLVED_DELEGATION_CALL, descriptor]
    if (delegatedCall != null) {
        val delegatedConstructor = delegatedCall.resultingDescriptor
        val receiver = JsirExpression.This()
        val outerReceiver = generateReceiver(delegatedCall) { outerParameter!!.makeReference() }.first
        val arguments = outerReceiver.singletonOrEmptyList() + generateArguments(delegatedCall, generateRawArguments(delegatedCall))
        val invocation = JsirExpression.Invocation(receiver, delegatedConstructor, false, *arguments.toTypedArray())

        append(invocation)
    }
}

internal fun JsirContext.generateAccessor(psi: KtPropertyAccessor?, accessor: VariableAccessorDescriptor?) {
    if (accessor == null) return

    if (psi != null && psi.bodyExpression != null) {
        generate(psi)
    }
    else {
        val function = JsirFunction(accessor, container!!, false)
        val isGetter = accessor.valueParameters.isEmpty()
        val container = accessor.correspondingVariable.containingDeclaration
        val receiver = if (container is ClassDescriptor) {
            JsirExpression.This()
        }
        else {
            null
        }
        val access = JsirExpression.FieldAccess(receiver, JsirField.Backing(accessor.correspondingVariable))
        nestedFunction(function, null, null) {
            nestedBlock(function.body) {
                if (isGetter) {
                    append(JsirStatement.Return(access, accessor))
                }
                else {
                    val parameter = variableContainer!!.createVariable(false, accessor.correspondingVariable.name.asString())
                    function.parameters += JsirParameter(parameter)
                    append(JsirStatement.Assignment(access, parameter.makeReference()))
                }
            }
        }
    }
}