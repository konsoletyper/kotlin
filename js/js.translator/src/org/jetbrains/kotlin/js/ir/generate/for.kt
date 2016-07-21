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

import org.jetbrains.kotlin.descriptors.VariableDescriptor
import org.jetbrains.kotlin.js.ir.JsirExpression
import org.jetbrains.kotlin.js.ir.JsirStatement
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.resolve.BindingContext

fun JsirContext.generateFor(psi: KtForExpression, label: String?): JsirExpression {
    val statement = JsirStatement.For(JsirExpression.Constant(true))

    val rangePsi = psi.loopRange!!
    val hasNextCall = bindingContext[BindingContext.LOOP_RANGE_HAS_NEXT_RESOLVED_CALL, rangePsi]!!

    val iterator = withSource(rangePsi) {
        generateIterator(rangePsi, statement)
    }

    nestedLabel(label, statement, true, true) {
        nestedBlock(statement.body) {
            withSource(psi.loopRange) {
                val condition = generateInvocation(hasNextCall) { iterator }
                val conditionalBreak = JsirStatement.If(condition.negate())
                append(conditionalBreak)
                nestedBlock(conditionalBreak.thenBody) {
                    append(JsirStatement.Break(statement))
                }
            }

            generateElement(psi, iterator)

            psi.body?.let { generate(it) }
        }
    }

    append(statement)
    return JsirExpression.Undefined
}

private fun JsirContext.generateIterator(rangePsi: KtExpression, statement: JsirStatement.For): JsirExpression {
    val iteratorCall = bindingContext[BindingContext.LOOP_RANGE_ITERATOR_RESOLVED_CALL, rangePsi]!!

    val iteratorStatements = mutableListOf<JsirStatement>()
    var iterator: JsirExpression = JsirExpression.Undefined
    nestedBlock(iteratorStatements) {
        iterator = memoize(generateInvocation(iteratorCall) { generate(rangePsi) })
    }

    val assignmentIndex = iteratorStatements.indexOfLast { it !is JsirStatement.Assignment }
    for (i in 0..assignmentIndex) {
        append(iteratorStatements[i])
    }
    statement.preAssignments += iteratorStatements
            .subList(assignmentIndex + 1, iteratorStatements.size)
            .map { it as JsirStatement.Assignment }

    return iterator
}

private fun JsirContext.generateElement(psi: KtForExpression, iterator: JsirExpression) {
    val nextCall = bindingContext[BindingContext.LOOP_RANGE_NEXT_RESOLVED_CALL, psi.loopRange!!]!!

    val destructuringParameter = psi.destructuringParameter
    val element = generateInvocation(nextCall) { iterator }
    if (destructuringParameter != null) {
        val memoizedElement = memoize(element)
        for (componentPsi in destructuringParameter.entries) {
            val componentCall = bindingContext[BindingContext.COMPONENT_RESOLVED_CALL, componentPsi]!!
            val parameterDescriptor = bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, componentPsi] as VariableDescriptor
            getVariable(parameterDescriptor).set(generateInvocation(componentCall) { memoizedElement })
        }
    }
    else {
        val parameterDescriptor = bindingContext[BindingContext.VALUE_PARAMETER, psi.loopParameter!!]!!
        getVariable(parameterDescriptor).set(element)
    }
}
