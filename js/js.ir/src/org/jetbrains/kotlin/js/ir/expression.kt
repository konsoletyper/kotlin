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
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.VariableDescriptor
import org.jetbrains.kotlin.types.KotlinType

sealed class JsirExpression {
    var source: PsiElement? = null

    class Constant(var value: Any?) : JsirExpression()

    object This : JsirExpression()

    object Undefined : JsirExpression()

    class VariableReference(var variable: JsirVariable, val free: Boolean) : JsirExpression()

    class FunctionReference(var function: FunctionDescriptor) : JsirExpression()

    class ObjectReference(var descriptor: ClassDescriptor) : JsirExpression()

    class Invocation(
            var receiver: JsirExpression?,
            var function: FunctionDescriptor,
            var virtual: Boolean,
            vararg arguments: JsirExpression
    ) : JsirExpression() {
        val arguments = mutableListOf(*arguments)
    }

    class Application(var function: JsirExpression, vararg arguments: JsirExpression) : JsirExpression() {
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

    class FieldAccess(var receiver: JsirExpression?, var field: JsirField) : JsirExpression()

    class Conditional(
            var condition: JsirExpression,
            var thenExpression: JsirExpression,
            var elseExpression: JsirExpression
    ) : JsirExpression()

    class Binary(
            var operation: JsirBinaryOperation, var type: JsirType,
            var left: JsirExpression, var right: JsirExpression
    ) : JsirExpression()

    class Unary(var operation: JsirUnaryOperation, var type: JsirType, var operand: JsirExpression) : JsirExpression()

    class InstanceOf(var value: JsirExpression, var type: KotlinType) : JsirExpression()

    class Cast(var value: JsirExpression, var type: KotlinType) : JsirExpression()

    class PrimitiveCast(var value: JsirExpression, var sourceType: JsirType, var targetType: JsirType) : JsirExpression()

    class NewNullPointerExpression : JsirExpression()
}

enum class JsirBinaryOperation {
    ADD,
    SUB,
    MUL,
    DIV,
    REM,
    BIT_AND,
    BIT_OR,
    BIT_XOR,
    SHL,
    LSHR,
    ASHR,
    AND,
    OR,
    EQ,
    NE,
    REF_EQ,
    REF_NE,
    LT,
    LOE,
    GT,
    GOE,
    COMPARE,
    ARRAY_GET,
    EQUALS_METHOD
}

enum class JsirUnaryOperation {
    MINUS,
    NEGATION,
    ARRAY_LENGTH,
    TO_STRING,
    ARRAY_COPY
}

// TODO: use PrimitiveType? instead
enum class JsirType {
    BOOLEAN,
    BYTE,
    SHORT,
    CHAR,
    INT,
    LONG,
    FLOAT,
    DOUBLE,
    ANY
}

sealed class JsirField {
    class Backing(val property: VariableDescriptor) : JsirField()

    class OuterClass(val classDescriptor: ClassDescriptor) : JsirField()

    class Closure(val variable: JsirVariable) : JsirField()

    class Delegate(val suggestedName: String?) : JsirField()
}

