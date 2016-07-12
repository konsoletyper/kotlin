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

import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor

val FunctionDescriptor.receiverClass: ClassDescriptor?
    get() = (extensionReceiverParameter?.containingDeclaration ?: containingDeclaration) as? ClassDescriptor

fun getPrimitiveType(name: String) = when (name) {
    "kotlin.Boolean" -> JsirType.BOOLEAN
    "kotlin.Byte" -> JsirType.BYTE
    "kotlin.Short" -> JsirType.SHORT
    "kotlin.Int" -> JsirType.INT
    "kotlin.Long" -> JsirType.LONG
    "kotlin.Float" -> JsirType.FLOAT
    "kotlin.Double" -> JsirType.DOUBLE
    "kotlin.Char" -> JsirType.CHAR
    else -> JsirType.ANY
}