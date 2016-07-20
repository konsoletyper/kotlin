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

import com.google.dart.compiler.backend.js.ast.JsExpression
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.js.ir.JsirExpression
import org.jetbrains.kotlin.js.ir.isAnyFunction

abstract class AnyMethodRenderer : InvocationRenderer {
    override fun isApplicable(descriptor: FunctionDescriptor): Boolean {
        if (!descriptor.isAnyFunction()) return false

        return matchNameAndArgumentCount(descriptor.name.asString(), descriptor.valueParameters.size)
    }

    protected abstract fun matchNameAndArgumentCount(name: String, argumentCount: Int): Boolean

    override fun render(invocation: JsirExpression.Invocation, context: JsirRenderingContext): JsExpression {
        val receiver = context.render(invocation.receiver!!)
        val arguments = invocation.arguments.map { context.render(it) }
        return render(context, receiver, arguments)
    }

    protected abstract fun render(context: JsirRenderingContext, receiver: JsExpression, arguments: List<JsExpression>): JsExpression
}
