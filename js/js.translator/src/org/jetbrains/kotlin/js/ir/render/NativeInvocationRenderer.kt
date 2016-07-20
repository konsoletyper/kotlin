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

package org.jetbrains.kotlin.js.ir.render

import com.google.dart.compiler.backend.js.ast.*
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.js.ir.JsirExpression
import org.jetbrains.kotlin.js.translate.utils.AnnotationsUtils
import org.jetbrains.kotlin.js.translate.utils.JsAstUtils
import org.jetbrains.kotlin.js.translate.utils.ManglingUtils

class NativeInvocationRenderer() : InvocationRenderer {
    override fun isApplicable(descriptor: FunctionDescriptor): Boolean {
        if (isApplicableDirectly(descriptor)) return true
        if (descriptor is VariableAccessorDescriptor && isApplicableDirectly(descriptor.correspondingVariable)) return true
        return false
    }

    private fun isApplicableDirectly(descriptor: DeclarationDescriptor): Boolean {
        return AnnotationsUtils.isNativeObject(descriptor) || AnnotationsUtils.isLibraryObject(descriptor)
    }

    override fun render(invocation: JsirExpression.Invocation, context: JsirRenderingContext): JsExpression {
        val receiver = invocation.receiver?.let { context.render(it) }
        val function = invocation.function.original

        if (function is VariableAccessorDescriptor) {
            val property = function.correspondingVariable
            val qualifier = receiver ?: (property.containingDeclaration as? ClassDescriptor)?.jsExpression(context)
            val name = property.getJsName()
            val reference = JsNameRef(name, qualifier)
            return if (property.getter == function) {
                reference
            }
            else {
                JsAstUtils.assignment(reference, context.render(invocation.arguments[0]))
            }
        }

        val lastParameter = function.valueParameters.lastOrNull()
        val varArg = lastParameter?.varargElementType != null
        val (arguments, renderVararg) = if (!varArg) {
            Pair(invocation.arguments.map { context.render(it) }, false)
        }
        else {
            val lastArgument = invocation.arguments.last()
            if (lastArgument is JsirExpression.ArrayOf) {
                Pair((invocation.arguments.dropLast(1) + lastArgument.elements).map { context.render(it) }, false)
            }
            else {
                Pair(invocation.arguments.map { context.render(it) }, true)
            }
        }

        return if (receiver == null) {
            if (!renderVararg) {
                JsInvocation(function.jsExpression(context), arguments)
            }
            else {
                createInvocation(function.jsExpression(context), JsLiteral.NULL, arguments)
            }
        }
        else if (invocation.virtual) {
            if (!renderVararg) {
                JsInvocation(JsNameRef(function.getJsName(), receiver), arguments)
            }
            else {
                createInvocation(JsNameRef(function.getJsName(), receiver), receiver, arguments)
            }
        }
        else {
            if (!renderVararg) {
                JsInvocation(JsNameRef("call", function.jsExpression(context)), listOf(receiver) + arguments)
            }
            else {
                createInvocation(function.jsExpression(context), receiver, arguments)
            }
        }
    }

    private fun DeclarationDescriptor.getSimpleJsName(): String {
        return if (AnnotationsUtils.isNativeObject(this)) {
            name.asString()
        }
        else {
            ManglingUtils.getSuggestedName(this)
        }
    }

    private fun DeclarationDescriptor.getJsName() = AnnotationsUtils.getNameForAnnotatedObjectWithOverrides(this) ?: getSimpleJsName()

    private fun DeclarationDescriptor.jsExpression(context: JsirRenderingContext): JsExpression {
        val cls = containingDeclaration as? ClassDescriptor
        val root = if (AnnotationsUtils.isLibraryObject(this)) context.kotlinName().makeRef() else null
        val qualifier = cls?.let { JsNameRef(cls.name.asString(), root) } ?: root
        return JsNameRef(getJsName(), qualifier)
    }

    private fun createInvocation(function: JsExpression, receiver: JsExpression, arguments: List<JsExpression>): JsInvocation {
        val regularArguments = JsArrayLiteral(listOf(receiver) + arguments.dropLast(1))
        val lastArgument = arguments.last()
        val argumentArray = JsInvocation(JsNameRef("concat", regularArguments), lastArgument)
        return JsInvocation(JsNameRef("invoke", function), argumentArray)
    }
}
