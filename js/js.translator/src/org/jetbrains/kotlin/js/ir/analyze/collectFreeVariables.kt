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

package org.jetbrains.kotlin.js.ir.analyze

import org.jetbrains.kotlin.js.ir.*

fun JsirFunction.collectFreeVariables(): Set<JsirVariable> {
    val container = JsirVariableContainer.Function(this)
    return body.collectFreeVariables(container) + parameters.flatMap { it.defaultBody.collectFreeVariables(container) }
}

private fun List<JsirStatement>.collectFreeVariables(container: JsirVariableContainer): Set<JsirVariable> {
    val variables = mutableSetOf<JsirVariable>()
    visit(object : JsirVisitor<Unit, Unit> {
        override fun accept(statement: JsirStatement, inner: () -> Unit) = inner()

        override fun accept(expression: JsirExpression, inner: () -> Unit) {
            if (expression is JsirExpression.VariableReference && expression.variable.container != container) {
                variables += expression.variable
            }
            inner()
        }
    })
    return variables
}
