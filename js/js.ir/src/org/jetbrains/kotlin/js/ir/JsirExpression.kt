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

package org.jetbrains.kotlin.js.ir

import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.PropertyDescriptor

sealed class JsirExpression {
    var source: PsiElement? = null

    class Constant(var value: Any) : JsirExpression()

    object Null : JsirExpression()

    object This : JsirExpression()

    object True : JsirExpression()

    object False : JsirExpression()

    object Undefined : JsirExpression()

    class VariableReference(var variable: JsirVariable) : JsirExpression()

    class Invocation(
            var receiver: JsirExpression?,
            var function: FunctionDescriptor,
            var virtual: Boolean,
            vararg arguments: JsirExpression
    ) : JsirExpression() {
        val arguments = mutableListOf(*arguments)
    }

    class NewInstance(var constructor: ConstructorDescriptor, vararg arguments: JsirExpression) : JsirExpression() {
        val arguments = mutableListOf(*arguments)
    }

    class ArrayOf(vararg elements: JsirExpression) : JsirExpression() {
        val elements = mutableListOf(*elements)
    }

    class Concat(vararg parts: JsirExpression) : JsirExpression() {
        val parts = mutableListOf(*parts)
    }

    class ToString(var value: JsirExpression) : JsirExpression()

    class FieldAccess(var receiver: JsirExpression?, var field: JsirField) : JsirExpression()

    class Conditional(
            var condition: JsirExpression,
            var thenExpression: JsirExpression,
            var elseExpression: JsirExpression
    ) : JsirExpression()

    class Binary(var operation: JsirBinaryOperation, var left: JsirExpression, var right: JsirExpression) : JsirExpression()

    class Negation(var operand: JsirExpression) : JsirExpression()

    class UnaryMinus(var operand: JsirExpression) : JsirExpression()

    class ArrayLength(var operand: JsirExpression) : JsirExpression()

    class NewNullPointerExpression : JsirExpression()
}
