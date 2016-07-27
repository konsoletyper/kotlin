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

import org.jetbrains.kotlin.backend.common.CodegenUtil
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.js.ir.*
import org.jetbrains.kotlin.js.translate.utils.BindingUtils
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDelegatedSuperTypeEntry
import org.jetbrains.kotlin.psi.KtSuperTypeListEntry
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.descriptorUtil.isExtension
import org.jetbrains.kotlin.utils.singletonOrEmptyList

internal class DelegationGenerator(val context: JsirContext, val psi: KtClassOrObject, val cls: JsirClass) {
    private val descriptor = BindingUtils.getClassDescriptor(context.bindingContext, psi)
    private val delegates: List<DelegateSpecifier>

    init {
        val delegates = mutableListOf<DelegateSpecifier>()
        for (specifier in psi.getSuperTypeListEntries().filterIsInstance<KtDelegatedSuperTypeEntry>()) {
            val expression = specifier.delegateExpression ?: error("delegate expression should not be null: ${specifier.text}")
            val superClass = getSuperClass(specifier)
            val propertyDescriptor = CodegenUtil.getDelegatePropertyIfAny(expression, descriptor, bindingContext)

            val field = if (CodegenUtil.isFinalPropertyWithBackingField(propertyDescriptor, bindingContext)) {
                JsirField.Backing(propertyDescriptor!!)
            }
            else {
                val delegateName = superClass.name.asString()
                val generatedField = JsirField.Delegate(delegateName)
                cls.delegateFields += generatedField
                generatedField
            }

            delegates += DelegateSpecifier(specifier, field, superClass)
        }
        this.delegates = delegates
    }

    fun contributeToInitializer() {
        for ((specifier, field) in delegates) {
            if (field is JsirField.Delegate) {
                context.withSource(specifier) {
                    val expression = specifier.delegateExpression!!
                    context.assign(JsirExpression.FieldAccess(JsirExpression.This(), field), context.generate(expression))
                }
            }
        }
    }

    fun generateMembers() {
        for ((psi, field, superClass) in delegates) {
            generateDelegates(psi, superClass, field)
        }
    }

    private fun generateDelegates(psi: KtDelegatedSuperTypeEntry, toClass: ClassDescriptor, field: JsirField) {
        for ((descriptor, overriddenDescriptor) in CodegenUtil.getDelegates(descriptor, toClass)) {
            context.withSource(psi) {
                when (descriptor) {
                    is PropertyDescriptor -> {
                        val overriddenProperty = overriddenDescriptor as PropertyDescriptor
                        val getter = descriptor.getter
                        if (getter != null) {
                            generateDelegateCallForFunction(getter, overriddenProperty.getter!!, field)
                        }
                        val setter = descriptor.setter
                        if (setter != null) {
                            generateDelegateCallForFunction(setter, overriddenDescriptor.setter!!, field)
                        }
                    }
                    is FunctionDescriptor ->
                        generateDelegateCallForFunction(descriptor, overriddenDescriptor as FunctionDescriptor, field)
                    else ->
                        error("Expected property or function $descriptor")
                }
            }
        }
    }

    private fun generateDelegateCallForFunction(
            descriptor: FunctionDescriptor, overriddenDescriptor: FunctionDescriptor,
            field : JsirField
    ) {
        val function = JsirFunction(descriptor, cls, static = false)
        val extensionParameter = if (descriptor.isExtension) {
            function.variableContainer.createVariable(false, "\$receiver")
        }
        else {
            null
        }
        context.nestedFunction(function, null, null) {
            context.nestedBlock(function.body) {
                val parameters = extensionParameter.singletonOrEmptyList() +
                                 descriptor.valueParameters.map { context.getVariable(it).localVariable }
                function.parameters += parameters.map { JsirParameter(it) }
                val arguments = parameters.map { it.makeReference() }.toTypedArray()
                val receiver = JsirExpression.FieldAccess(JsirExpression.This(), field)
                val invocation = JsirExpression.Invocation(receiver, overriddenDescriptor, true, *arguments)
                context.append(JsirStatement.Return(invocation, descriptor))
            }
        }
    }

    private fun getSuperClass(specifier: KtSuperTypeListEntry): ClassDescriptor =
            CodegenUtil.getSuperClassBySuperTypeListEntry(specifier, bindingContext)

    private val bindingContext: BindingContext
        get() = context.bindingContext

    data class DelegateSpecifier(val psi: KtDelegatedSuperTypeEntry, val field: JsirField, val superClass: ClassDescriptor)
}