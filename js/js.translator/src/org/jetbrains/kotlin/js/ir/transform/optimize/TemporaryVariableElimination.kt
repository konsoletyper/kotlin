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

package org.jetbrains.kotlin.js.ir.transform.optimize

import org.jetbrains.kotlin.js.ir.*
import org.jetbrains.kotlin.utils.singletonOrEmptyList
import kotlin.reflect.KClass

class TemporaryVariableElimination : Optimization {
    override fun apply(variableContainer: JsirVariableContainer, body: MutableList<JsirStatement>): List<KClass<out Optimization>> {
        val implementation = Implementation(variableContainer, body)
        implementation.analyze()
        implementation.perform()
        implementation.cleanUp()
        return if (implementation.changed) {
            listOf(TemporaryVariableElimination::class, CompareToElimination::class)
        }
        else {
            emptyList()
        }
    }
}

private class Implementation(val variableContainer: JsirVariableContainer, val body: MutableList<JsirStatement>) {
    private val definitions = mutableMapOf<JsirVariable, Int>()
    private val definedValues = mutableMapOf<JsirVariable, JsirExpression>()
    private val usages = mutableMapOf<JsirVariable, Int>()
    private val variablesToSubstitute = mutableSetOf<JsirVariable>()
    private val variablesToRemove = mutableSetOf<JsirVariable>()
    private val statementsToRemove = mutableSetOf<JsirStatement.Assignment>()
    private val variablesWithSideEffects = mutableSetOf<JsirVariable>()
    var changed = false

    fun analyze() {
        body.visit(object : JsirVisitor<Unit, Unit> {
            override fun accept(expression: JsirExpression, inner: () -> Unit) {
                when (expression) {
                    is JsirExpression.VariableReference -> {
                        usages[expression.variable] = 1 + (usages[expression.variable] ?: 0)
                    }
                }
                inner()
            }

            override fun accept(statement: JsirStatement, inner: () -> Unit) {
                when (statement) {
                    is JsirStatement.Assignment -> {
                        val left = statement.left
                        if (left is JsirExpression.VariableReference) {
                            val definitionCount = if (left.variable in variableContainer.variables) 1 else 2
                            definitions[left.variable] = definitionCount + (definitions[left.variable] ?: 0)
                            definedValues[left.variable] = statement.right
                            statement.right.visit(this)
                            return
                        }
                    }
                }
                inner()
            }
        })
    }

    fun perform() {
        val lastAssignedVars = mutableListOf<Pair<JsirVariable, JsirStatement.Assignment>>()

        body.visit(object : JsirVisitor<Unit, Unit> {
            override fun accept(statement: JsirStatement, inner: () -> Unit) {
                when (statement) {
                    is JsirStatement.Assignment -> {
                        val left = statement.left
                        if (left is JsirExpression.VariableReference) {
                            handleDefinition(left.variable, statement.right, statement)
                        }
                        else {
                            if (left != null) {
                                handleExpression(*(extractLhsQualifier(left) + statement.right).toTypedArray())
                                invalidateTemporaries()
                            }
                            else {
                                if (handleExpression(statement.right)) {
                                    invalidateTemporaries()
                                }
                            }
                        }
                    }
                    is JsirStatement.If -> {
                        handleExpression(statement.condition)
                        invalidateTemporaries()
                        statement.thenBody.visit(this)
                        invalidateTemporaries()
                        statement.elseBody.visit(this)
                        invalidateTemporaries()
                    }
                    is JsirStatement.Return -> {
                        statement.value?.let { it.visit(this) }
                        invalidateTemporaries()
                    }
                    is JsirStatement.Throw -> {
                        statement.exception.visit(this)
                        invalidateTemporaries()
                    }
                    is JsirStatement.Switch -> {
                        handleExpression(statement.selector)
                        invalidateTemporaries()
                        for (caseClause in statement.clauses) {
                            caseClause.body.visit(this)
                            invalidateTemporaries()
                        }
                        statement.defaultClause.visit(this)
                        invalidateTemporaries()
                    }
                    is JsirStatement.While -> {
                        invalidateTemporaries()
                        statement.body.visit(this)
                        invalidateTemporaries()
                    }
                    is JsirStatement.DoWhile -> {
                        invalidateTemporaries()
                        statement.body.visit(this)
                        invalidateTemporaries()
                    }
                    is JsirStatement.For -> {
                        statement.preAssignments.visit(this)
                        invalidateTemporaries()
                        statement.body.visit(this)
                        invalidateTemporaries()
                    }
                    is JsirStatement.Try -> {
                        invalidateTemporaries()
                        statement.body.visit(this)
                        for (clause in statement.catchClauses) {
                            invalidateTemporaries()
                            clause.body.visit(this)
                        }
                        invalidateTemporaries()
                        statement.finallyClause.visit(this)
                        invalidateTemporaries()
                    }
                    is JsirStatement.Break,
                    is JsirStatement.Continue -> invalidateTemporaries()
                }
            }

            private fun extractLhsQualifier(expression: JsirExpression) = when (expression) {
                is JsirExpression.FieldAccess -> expression.receiver.singletonOrEmptyList()
                is JsirExpression.Binary -> {
                    if (expression.operation == JsirBinaryOperation.ARRAY_GET) {
                        listOf(expression.left, expression.right)
                    }
                    else {
                        listOf(expression)
                    }
                }
                else -> listOf(expression)
            }

            override fun accept(expression: JsirExpression, inner: () -> Unit) { }

            private fun handleDefinition(name: JsirVariable, value: JsirExpression, node: JsirStatement.Assignment) {
                val sideEffects = handleExpression(value)
                if (shouldConsiderTemporary(name)) {
                    if (isTrivial(value)) {
                        statementsToRemove += node
                        variablesToSubstitute += name
                    }
                    else {
                        lastAssignedVars += Pair(name, node)
                        if (sideEffects) {
                            variablesWithSideEffects += name
                        }
                    }
                }
                else {
                    if (shouldConsiderUnused(name)) {
                        variablesToRemove += name
                        if (sideEffects) {
                            invalidateTemporaries()
                        }
                    }
                    else {
                        invalidateTemporaries()
                    }
                }
            }

            private fun invalidateTemporaries() = lastAssignedVars.clear()

            private fun handleExpression(vararg expression: JsirExpression): Boolean {
                val candidateFinder = SubstitutionCandidateFinder()
                var sideEffects = expression.firstOrNull { it.visit(candidateFinder) } != null

                var candidates = candidateFinder.substitutableVariableReferences
                while (lastAssignedVars.isNotEmpty()) {
                    val (assignedVar, assignedStatement) = lastAssignedVars.last()
                    val candidateIndex = candidates.lastIndexOf(assignedVar)
                    if (candidateIndex < 0) break

                    variablesToSubstitute += assignedVar
                    statementsToRemove += assignedStatement
                    if (assignedVar in variablesWithSideEffects) {
                        sideEffects = true
                    }
                    candidates = candidates.subList(0, candidateIndex)
                    lastAssignedVars.removeAt(lastAssignedVars.lastIndex)
                }

                return sideEffects
            }
        })
    }

    fun cleanUp() {
        body.replace(object : JsirMapper {
            override fun map(statement: JsirStatement, canChangeType: Boolean) = when (statement) {
                is JsirStatement.Assignment -> {
                    if (statement in statementsToRemove) {
                        changed = true
                        null
                    }
                    else {
                        val left = statement.left
                        if (left is JsirExpression.VariableReference && left.variable in variablesToRemove) {
                            changed = true
                            statement.left = null
                        }
                        statement
                    }
                }
                else -> statement
            }

            override fun map(expression: JsirExpression) =  when (expression) {
                is JsirExpression.VariableReference -> {
                    val name = expression.variable
                    if (name in variablesToSubstitute) {
                        changed = true
                        definedValues[name]!!.replace(this)
                    }
                    else {
                        expression
                    }
                }
                else -> expression
            }
        })

        for (variable in variablesToSubstitute + variablesToRemove) {
            variable.delete()
        }
    }

    private inner class SubstitutionCandidateFinder : JsirVisitor<Unit, Boolean> {
        val substitutableVariableReferences = mutableListOf<JsirVariable>()

        override fun accept(statement: JsirStatement, inner: () -> Unit) { }

        override fun accept(expression: JsirExpression, inner: () -> Unit): Boolean {
            return when (expression) {
                is JsirExpression.NewInstance -> {
                    expression.arguments.firstOrNull { !it.visit(this) }
                    true
                }
                is JsirExpression.Invocation -> {
                    (expression.receiver?.visit(this) ?: false) || expression.arguments.firstOrNull { it.visit(this) } != null
                    true
                }
                is JsirExpression.Application -> {
                    expression.function.visit(this) || expression.arguments.firstOrNull { it.visit(this) } != null
                    true
                }
                is JsirExpression.FieldAccess -> {
                    expression.receiver?.visit(this)
                    true
                }
                is JsirExpression.Conditional -> {
                    val conditionResult = expression.condition.visit(this)
                    val listLength = substitutableVariableReferences.size
                    val result = conditionResult || expression.thenExpression.visit(this) || expression.elseExpression.visit(this)
                    substitutableVariableReferences.subList(listLength, substitutableVariableReferences.size).clear()
                    result
                }
                is JsirExpression.Binary -> {
                    val result = expression.left.visit(this) || expression.right.visit(this)
                    when (expression.operation) {
                        JsirBinaryOperation.EQUALS_METHOD,
                        JsirBinaryOperation.COMPARE -> true
                        else -> result
                    }
                }
                is JsirExpression.Unary -> {
                    val result = expression.operand.visit(this)
                    when (expression.operation) {
                        JsirUnaryOperation.ARRAY_COPY,
                        JsirUnaryOperation.ARRAY_LENGTH,
                        JsirUnaryOperation.TO_STRING -> true
                        else -> result
                    }
                }
                is JsirExpression.ArrayOf -> {
                    expression.elements.firstOrNull { it.visit(this) }
                    true
                }
                is JsirExpression.VariableReference -> {
                    val variable = expression.variable
                    if (variable !in variablesToSubstitute && shouldConsiderTemporary(variable)) {
                        substitutableVariableReferences += variable
                    }
                    false
                }
                is JsirExpression.Concat -> {
                    expression.parts.firstOrNull { it.visit(this) } != null
                }
                is JsirExpression.Cast -> {
                    expression.value.visit(this)
                    true
                }
                is JsirExpression.PrimitiveCast -> {
                    expression.value.visit(this)
                }
                is JsirExpression.InstanceOf -> expression.value.visit(this)

                is JsirExpression.NewNullPointerExpression -> false

                is JsirExpression.ObjectReference -> true
                is JsirExpression.FunctionReference -> true

                is JsirExpression.Undefined,
                is JsirExpression.This,
                is JsirExpression.Constant -> false
            }
        }
    }

    private fun shouldConsiderUnused(variable: JsirVariable) = definitions[variable] == 1 && (usages[variable] ?: 0) == 0 &&
                                                               variable.suggestedName == null

    private fun shouldConsiderTemporary(variable: JsirVariable): Boolean {
        if (variable.suggestedName != null) return false
        if (definitions[variable] != 1) return false

        val expr = definedValues[variable]
        // It's useful to copy trivial expressions when they are used more than once. Example are temporary variables
        // that receiver another (non-temporary) variables. To prevent code from bloating, we don't treat large value literals
        // as trivial expressions.
        return (expr != null && isTrivial(expr)) || usages[variable] == 1
    }

    private fun isTrivial(expression: JsirExpression): Boolean = when (expression) {
        is JsirExpression.VariableReference -> {
            val variable = expression.variable
            when (definitions[variable]) {
                null, 0 -> expression.variable in variableContainer.variables
                1 -> variable !in variablesToSubstitute || definedValues[variable]?.let { isTrivial(it) } ?: false
                else -> false
            }
        }
        is JsirExpression.Constant -> expression.value.toString().length < 10
        is JsirExpression.This,
        is JsirExpression.Undefined -> true
        else -> false
    }
}
