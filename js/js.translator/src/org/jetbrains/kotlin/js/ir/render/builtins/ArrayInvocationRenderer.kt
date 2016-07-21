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

package org.jetbrains.kotlin.js.ir.render.builtins

import com.google.dart.compiler.backend.js.ast.JsExpression
import com.google.dart.compiler.backend.js.ast.JsInvocation
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.builtins.PrimitiveType
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.js.ir.JsirExpression
import org.jetbrains.kotlin.js.ir.render.InvocationRenderer
import org.jetbrains.kotlin.js.ir.render.JsirRenderingContext
import org.jetbrains.kotlin.js.ir.render.kotlinReference
import org.jetbrains.kotlin.js.patterns.NamePredicate
import org.jetbrains.kotlin.js.patterns.PatternBuilder.pattern
import org.jetbrains.kotlin.utils.singletonOrEmptyList

class ArrayInvocationRenderer : InvocationRenderer {
    private val NUMBER_ARRAY: NamePredicate
    private val CHAR_ARRAY = NamePredicate(PrimitiveType.CHAR.arrayTypeName)
    private val BOOLEAN_ARRAY = NamePredicate(PrimitiveType.BOOLEAN.arrayTypeName)
    private val LONG_ARRAY: NamePredicate = NamePredicate(PrimitiveType.LONG.arrayTypeName)
    private val ARRAYS: NamePredicate

    init {
        val excludeArrayTypes = setOf(PrimitiveType.CHAR, PrimitiveType.BOOLEAN, PrimitiveType.LONG)
        val arrayTypeNames = (PrimitiveType.values().asSequence() - excludeArrayTypes)
                .map { it.arrayTypeName }
                .toList()
        NUMBER_ARRAY = NamePredicate(arrayTypeNames)

        val allArrayTypeNames = PrimitiveType.values().map { it.arrayTypeName } + KotlinBuiltIns.FQ_NAMES.array.shortName()
        ARRAYS = NamePredicate(allArrayTypeNames)
    }

    private val patterns = listOf(
            Pair(pattern(ARRAYS, "iterator"), "arrayIterator"),
            Pair(pattern(NUMBER_ARRAY, "<init>(Int)"), "numberArrayOfSize"),
            Pair(pattern(CHAR_ARRAY, "<init>(Int)"), "charArrayOfSize"),
            Pair(pattern(BOOLEAN_ARRAY, "<init>(Int)"), "booleanArrayOfSize"),
            Pair(pattern(BOOLEAN_ARRAY, "<init>(Int)"), "booleanArrayOfSize"),
            Pair(pattern(LONG_ARRAY, "<init>(Int)"), "longArrayOfSize"),
            Pair(pattern(ARRAYS, "<init>(Int,Function1)"), "arrayFromFun")
    )

    override fun isApplicable(descriptor: FunctionDescriptor) = patterns.any { it.first.apply(descriptor) }

    override fun render(
            function: FunctionDescriptor, receiver: JsirExpression?, arguments: List<JsirExpression>,
            virtual: Boolean, context: JsirRenderingContext
    ): JsExpression {
        val functionName = patterns.find { it.first.apply(function) }!!.second
        val allArguments = receiver.singletonOrEmptyList() + arguments
        return JsInvocation(context.kotlinReference(functionName), allArguments.map { context.render(it) })
    }
}