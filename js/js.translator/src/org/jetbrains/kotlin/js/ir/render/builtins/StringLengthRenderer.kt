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
import com.google.dart.compiler.backend.js.ast.JsNameRef
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.VariableAccessorDescriptor
import org.jetbrains.kotlin.js.ir.JsirExpression
import org.jetbrains.kotlin.js.ir.receiverClass
import org.jetbrains.kotlin.js.ir.render.InvocationRenderer
import org.jetbrains.kotlin.js.ir.render.JsirRenderingContext
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe

class StringLengthRenderer : InvocationRenderer {
    override fun isApplicable(descriptor: FunctionDescriptor): Boolean {
        if (descriptor.receiverClass?.fqNameSafe?.asString() != "kotlin.String") return false
        if (descriptor !is VariableAccessorDescriptor) return false

        return descriptor.correspondingVariable.name.asString() == "length"
    }

    override fun render(
            function: FunctionDescriptor, receiver: JsirExpression?, arguments: List<JsirExpression>,
            virtual: Boolean, context: JsirRenderingContext
    ): JsExpression {
        return JsNameRef("length", context.render(receiver!!))
    }
}
