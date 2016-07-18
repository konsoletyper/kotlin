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

interface JsirVisitor<S, E> {
    fun accept(statement: JsirStatement, inner: () -> Unit): S

    fun accept(expression: JsirExpression, inner: () -> Unit): E
}

interface JsirMapper {
    fun map(statement: JsirStatement, canChangeType: Boolean): JsirStatement?

    fun map(expression: JsirExpression): JsirExpression
}

fun <S, E> JsirStatement.visit(visitor: JsirVisitor<S, E>): S = visitor.accept(this, when (this) {
    is JsirStatement.Assignment -> ({
        left?.let { it.visit(visitor) }
        right.visit(visitor)
        Unit
    })
    is JsirStatement.Block -> ({ body.visit(visitor) })
    is JsirStatement.Break,
    is JsirStatement.Continue -> ({})
    is JsirStatement.Return -> ({ value?.let { it.visit(visitor) } })
    is JsirStatement.Throw -> ({ exception.visit(visitor) })
    is JsirStatement.If -> ({
        condition.visit(visitor)
        thenBody.visit(visitor)
        elseBody.visit(visitor)
    })
    is JsirStatement.While -> ({
        condition.visit(visitor)
        body.visit(visitor)
    })
    is JsirStatement.DoWhile -> ({
        condition.visit(visitor)
        body.visit(visitor)
    })
    is JsirStatement.For -> ({
        preAssignments.visit(visitor)
        condition.visit(visitor)
        postAssignments.visit(visitor)
        body.visit(visitor)
    })
    is JsirStatement.Switch -> ({
        selector.visit(visitor)
        for (clause in clauses) {
            clause.value.visit(visitor)
            clause.body.visit(visitor)
        }
        defaultClause.visit(visitor)
    })
    is JsirStatement.Try -> ({
        body.visit(visitor)
        for (clause in catchClauses) {
            clause.body.visit(visitor)
        }
        finallyClause.visit(visitor)
    })
})

@JvmName("visitStatements")
fun <S, E> List<JsirStatement>.visit(visitor: JsirVisitor<S, E>) = forEach { it.visit(visitor) }

fun <S, E> JsirExpression.visit(visitor: JsirVisitor<S, E>): E = visitor.accept(this, when (this) {
    is JsirExpression.ArrayOf -> ({ elements.visit(visitor) })
    is JsirExpression.Concat -> ({ parts.visit(visitor) })
    is JsirExpression.Binary -> ({
        left.visit(visitor)
        right.visit(visitor)
    })
    is JsirExpression.Unary -> ({
        operand.visit(visitor)
    })
    is JsirExpression.Conditional -> ({
        condition.visit(visitor)
        thenExpression.visit(visitor)
        elseExpression.visit(visitor)
    })
    is JsirExpression.FieldAccess -> ({
        receiver?.let { it.visit(visitor) }
    })
    is JsirExpression.Invocation -> ({
        receiver?.let { it.visit(visitor) }
        arguments.visit(visitor)
    })
    is JsirExpression.Application -> ({
        function.visit(visitor)
        arguments.visit(visitor)
    })
    is JsirExpression.NewInstance -> ({
        arguments.visit(visitor)
    })

    is JsirExpression.InstanceOf -> ({
        value.visit(visitor)
    })
    is JsirExpression.Cast -> ({
        value.visit(visitor)
    })

    is JsirExpression.NewNullPointerExpression,
    is JsirExpression.VariableReference,
    is JsirExpression.FunctionReference,
    is JsirExpression.ObjectReference,
    is JsirExpression.Constant,
    is JsirExpression.This,
    is JsirExpression.Null,
    is JsirExpression.Undefined,
    is JsirExpression.True,
    is JsirExpression.False -> ({ })
})

@JvmName("visitExpressions")
fun <S, E> List<JsirExpression>.visit(visitor: JsirVisitor<S, E>) = forEach { it.visit(visitor) }

fun JsirStatement.replace(mapper: (JsirStatement, Boolean) -> JsirStatement?) = replace(object: JsirMapper {
    override fun map(statement: JsirStatement, canChangeType: Boolean) = mapper(statement, canChangeType)

    override fun map(expression: JsirExpression) = expression
})

fun JsirStatement.replace(mapper: (JsirExpression) -> JsirExpression) = replace(object: JsirMapper {
    override fun map(statement: JsirStatement, canChangeType: Boolean) = statement

    override fun map(expression: JsirExpression) = mapper(expression)
})

fun JsirStatement.replace(mapper: JsirMapper): JsirStatement? = replace(mapper, true)

private fun JsirStatement.replace(mapper: JsirMapper, canChangeType: Boolean): JsirStatement? = when (this) {
    is JsirStatement.Assignment -> {
        left = left?.replace(mapper)
        right = right.replace(mapper)
        mapper.map(this, canChangeType)
    }
    is JsirStatement.Block -> {
        body.replace(mapper)
        mapper.map(this, canChangeType)
    }
    is JsirStatement.If -> {
        condition = condition.replace(mapper)
        thenBody.replace(mapper)
        elseBody.replace(mapper)
        mapper.map(this, canChangeType)
    }
    is JsirStatement.While -> {
        condition = condition.replace(mapper)
        body.replace(mapper)
        mapper.map(this, canChangeType)
    }
    is JsirStatement.DoWhile -> {
        condition = condition.replace(mapper)
        body.replace(mapper)
        mapper.map(this, canChangeType)
    }
    is JsirStatement.For -> {
        preAssignments.replaceAssignments(mapper)
        condition = condition.replace(mapper)
        body.replace(mapper)
        postAssignments.replaceAssignments(mapper)
        mapper.map(this, canChangeType)
    }
    is JsirStatement.Switch -> {
        selector = selector.replace(mapper)
        for (clause in clauses) {
            clause.value = clause.value.replace(mapper)
            clause.body.replace(mapper)
        }
        defaultClause.replace(mapper)
        mapper.map(this, canChangeType)
    }
    is JsirStatement.Try -> {
        body.replace(mapper)
        for (clause in catchClauses) {
            clause.body.replace(mapper)
        }
        finallyClause.replace(mapper)
        mapper.map(this, canChangeType)
    }
    is JsirStatement.Return -> {
        value = value?.replace(mapper)
        mapper.map(this, canChangeType)
    }
    is JsirStatement.Throw -> {
        exception = exception.replace(mapper)
        mapper.map(this, canChangeType)
    }
    is JsirStatement.Break,
    is JsirStatement.Continue -> mapper.map(this, canChangeType)
}

fun JsirExpression.replace(mapper: JsirMapper): JsirExpression = when (this) {
    is JsirExpression.Constant,
    is JsirExpression.True,
    is JsirExpression.False,
    is JsirExpression.Null,
    is JsirExpression.Undefined,
    is JsirExpression.This,
    is JsirExpression.NewNullPointerExpression,
    is JsirExpression.FunctionReference,
    is JsirExpression.ObjectReference,
    is JsirExpression.VariableReference -> mapper.map(this)
    is JsirExpression.Binary -> {
        left = left.replace(mapper)
        right = right.replace(mapper)
        mapper.map(this)
    }
    is JsirExpression.Conditional -> {
        condition = condition.replace(mapper)
        thenExpression = thenExpression.replace(mapper)
        elseExpression = elseExpression.replace(mapper)
        mapper.map(this)
    }
    is JsirExpression.ArrayOf -> {
        elements.replace(mapper)
        mapper.map(this)
    }
    is JsirExpression.Unary -> {
        operand = operand.replace(mapper)
        mapper.map(this)
    }
    is JsirExpression.Concat -> {
        parts.replace(mapper)
        mapper.map(this)
    }
    is JsirExpression.Invocation -> {
        receiver = receiver?.replace(mapper)
        arguments.replace(mapper)
        mapper.map(this)
    }
    is JsirExpression.Application -> {
        function = function.replace(mapper)
        arguments.replace(mapper)
        mapper.map(this)
    }
    is JsirExpression.NewInstance -> {
        arguments.replace(mapper)
        mapper.map(this)
    }
    is JsirExpression.FieldAccess -> {
        receiver = receiver?.replace(mapper)
        mapper.map(this)
    }

    is JsirExpression.InstanceOf -> {
        value = value.replace(mapper)
        mapper.map(this)
    }
    is JsirExpression.Cast -> {
        value = value.replace(mapper)
        mapper.map(this)
    }
}

@JvmName("replaceStatements")
fun MutableList<JsirStatement>.replace(mapper: JsirMapper): MutableList<JsirStatement> {
    val iterator = listIterator()
    while (iterator.hasNext()) {
        val statement = iterator.next().replace(mapper, true)
        val newStatement = statement?.let { mapper.map(it, true) }
        if (newStatement == null) {
            iterator.remove()
        }
        else {
            iterator.set(newStatement)
        }
    }
    return this
}

@JvmName("replaceStatements")
fun MutableList<JsirStatement>.replace(mapper: (JsirExpression) -> JsirExpression) = replace(object: JsirMapper {
    override fun map(statement: JsirStatement, canChangeType: Boolean) = statement

    override fun map(expression: JsirExpression) = mapper(expression)
})

@JvmName("replaceExpressions")
fun MutableList<JsirExpression>.replace(mapper: JsirMapper): MutableList<JsirExpression> {
    val iterator = listIterator()
    while (iterator.hasNext()) {
        val expression = mapper.map(iterator.next().replace(mapper))
        iterator.set(expression)
    }
    return this
}

private fun MutableList<JsirStatement.Assignment>.replaceAssignments(mapper: JsirMapper): MutableList<JsirStatement.Assignment> {
    val iterator = listIterator()
    while (iterator.hasNext()) {
        val initialStatement = iterator.next()
        val statement = mapper.map(initialStatement, false)
        if (statement == null) {
            iterator.remove()
        }
        else if (statement !is JsirStatement.Assignment) {
            throw IllegalStateException("Mapper should not have returned statement of another type. " +
                                        "Initial was ${initialStatement::class.qualifiedName}, " +
                                        "new is ${statement::class.qualifiedName}")
        }
        else {
            iterator.set(statement)
        }
    }
    return this
}