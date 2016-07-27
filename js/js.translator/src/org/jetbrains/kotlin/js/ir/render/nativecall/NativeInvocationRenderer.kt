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

package org.jetbrains.kotlin.js.ir.render.nativecall

import com.google.dart.compiler.backend.js.ast.*
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.js.ir.JsirExpression
import org.jetbrains.kotlin.js.ir.render.InstantiationRenderer
import org.jetbrains.kotlin.js.ir.render.InvocationRenderer
import org.jetbrains.kotlin.js.ir.render.JsirRenderingContext
import org.jetbrains.kotlin.js.ir.render.kotlinName
import org.jetbrains.kotlin.js.translate.utils.AnnotationsUtils
import org.jetbrains.kotlin.js.translate.utils.JsAstUtils
import org.jetbrains.kotlin.js.translate.utils.ManglingUtils

class NativeInvocationRenderer() : InvocationRenderer, InstantiationRenderer {
    override fun isApplicable(descriptor: FunctionDescriptor): Boolean {
        if (isApplicableDirectly(descriptor.original)) return true
        if (descriptor is VariableAccessorDescriptor && isApplicableDirectly(descriptor.correspondingVariable.original)) return true
        return false
    }

    override fun isApplicable(descriptor: ConstructorDescriptor) = isApplicable(descriptor as FunctionDescriptor)

    private fun isApplicableDirectly(descriptor: DeclarationDescriptor): Boolean {
        if (descriptor is CallableMemberDescriptor && descriptor.kind == CallableMemberDescriptor.Kind.FAKE_OVERRIDE) {
            return generateSequence(descriptor) { it.overriddenDescriptors.firstOrNull()?.original }
                    .any { AnnotationsUtils.isNativeObject(it) || AnnotationsUtils.isLibraryObject(it) }
        }
        return AnnotationsUtils.isNativeObject(descriptor) || AnnotationsUtils.isLibraryObject(descriptor)
    }

    override fun render(
            function: FunctionDescriptor, receiver: JsirExpression?, arguments: List<JsirExpression>,
            virtual: Boolean, context: JsirRenderingContext
    ): JsExpression {
        val jsReceiver = receiver?.let { context.render(it) }
        val originalFunction = function.original

        if (originalFunction is VariableAccessorDescriptor) {
            val property = originalFunction.correspondingVariable
            val qualifier = jsReceiver ?: (property.containingDeclaration as? ClassDescriptor)?.jsExpression(context)
            val name = property.getJsName()
            val reference = JsNameRef(name, qualifier)
            return if (property.getter == originalFunction) {
                reference
            }
            else {
                JsAstUtils.assignment(reference, context.render(arguments[0]))
            }
        }

        val (jsArguments, renderVararg) = getCallArguments(originalFunction, arguments, context)

        return if (receiver == null) {
            if (!renderVararg) {
                JsInvocation(originalFunction.jsExpression(context), jsArguments)
            }
            else {
                createInvocation(originalFunction.jsExpression(context), JsLiteral.NULL, jsArguments)
            }
        }
        else if (virtual) {
            if (!renderVararg) {
                JsInvocation(JsNameRef(originalFunction.getJsName(), jsReceiver), jsArguments)
            }
            else {
                createInvocation(JsNameRef(originalFunction.getJsName(), jsReceiver), jsReceiver, jsArguments)
            }
        }
        else {
            if (!renderVararg) {
                JsInvocation(JsNameRef("call", originalFunction.jsExpression(context)), listOf(jsReceiver) + jsArguments)
            }
            else {
                createInvocation(originalFunction.jsExpression(context), jsReceiver, jsArguments)
            }
        }
    }

    override fun render(constructor: ConstructorDescriptor, arguments: List<JsirExpression>, context: JsirRenderingContext): JsExpression {
        val originalConstructor = constructor.original

        val constructorRef = originalConstructor.containingDeclaration.jsConstructor(context)
        val (jsArguments, renderVararg) = getCallArguments(originalConstructor, arguments, context)
        return if (!renderVararg) {
            JsNew(constructorRef, jsArguments)
        }
        else {
            val bind = JsNameRef("apply", JsNameRef("bind", "Function"))
            val reference = JsInvocation(bind, constructorRef, JsArrayLiteral(listOf(JsLiteral.NULL) + jsArguments))
            JsNew(reference)
        }
    }

    private fun getCallArguments(
            originalFunction: FunctionDescriptor,
            arguments: List<JsirExpression>,
            context: JsirRenderingContext
    ): Pair<List<JsExpression>, Boolean> {
        val lastParameter = originalFunction.valueParameters.lastOrNull()
        val varArg = lastParameter?.varargElementType != null
        return if (!varArg) {
            Pair(arguments.map { context.render(it) }, false)
        }
        else {
            val lastArgument = arguments.last()
            if (lastArgument is JsirExpression.ArrayOf) {
                Pair((arguments.dropLast(1) + lastArgument.elements).map { context.render(it) }, false)
            }
            else {
                Pair(arguments.map { context.render(it) }, true)
            }
        }
    }

    private fun DeclarationDescriptor.getSimpleJsName(): String {
        return if (AnnotationsUtils.isNativeObject(this)) {
            name.asString()
        }
        else {
            val overridden = if (this is CallableMemberDescriptor) {
                generateSequence(this) { it.overriddenDescriptors.firstOrNull() }.last().original
            }
            else {
                this
            }
            ManglingUtils.getSuggestedName(overridden)
        }
    }

    private fun DeclarationDescriptor.getJsName() = AnnotationsUtils.getNameForAnnotatedObjectWithOverrides(this) ?: getSimpleJsName()

    private fun DeclarationDescriptor.jsExpression(context: JsirRenderingContext): JsExpression {
        val cls = containingDeclaration as? ClassDescriptor
        val root = if (AnnotationsUtils.isLibraryObject(this)) context.kotlinName().makeRef() else null
        val qualifier = cls?.let { JsNameRef(cls.name.asString(), root) } ?: root
        if (this is ConstructorDescriptor) return qualifier!!

        return JsNameRef(getJsName(), qualifier)
    }

    private fun ClassDescriptor.jsConstructor(context: JsirRenderingContext): JsExpression {
        val root = if (AnnotationsUtils.isLibraryObject(this)) context.kotlinName().makeRef() else null
        return JsNameRef(getJsName(), root)
    }

    private fun createInvocation(function: JsExpression, receiver: JsExpression?, arguments: List<JsExpression>): JsInvocation {
        val regularArguments = JsArrayLiteral(listOf(receiver ?: JsLiteral.NULL) + arguments.dropLast(1))
        val lastArgument = arguments.last()
        val argumentArray = JsInvocation(JsNameRef("concat", regularArguments), lastArgument)
        return JsInvocation(JsNameRef("invoke", function), argumentArray)
    }
}
