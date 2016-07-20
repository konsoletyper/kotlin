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

import com.google.dart.compiler.backend.js.ast.*
import com.google.gwt.dev.js.ThrowExceptionOnErrorReporter
import org.jetbrains.kotlin.js.ir.JsirExpression
import org.jetbrains.kotlin.js.ir.prettyPrint
import org.jetbrains.kotlin.js.parser.parse
import org.jetbrains.kotlin.js.resolve.diagnostics.JsCallChecker

fun JsirExpression.Invocation.isJsCode() = JsCallChecker.isJsCallDescriptor(function)

fun JsirRenderingContext.renderJsCode(expression: JsirExpression.Invocation): Pair<List<JsStatement>, JsExpression> {
    val statements = parseJsCode(expression.arguments[0])
    return if (statements.size != 1) {
        Pair(statements, JsLiteral.NULL)
    }
    else {
        val resultStatement = statements[0]
        if (resultStatement is JsExpressionStatement) {
            Pair(emptyList<JsStatement>(), resultStatement.expression)
        }
        else {
            Pair(listOf<JsStatement>(resultStatement), JsLiteral.NULL)
        }
    }
}

private fun JsirRenderingContext.parseJsCode(codeExpression: JsirExpression): List<JsStatement> {
    val jsCode = extractStringValue(codeExpression) ?: error("jsCode must be compile time string " + codeExpression.prettyPrint())

    val scope = DelegatingJsFunctionScopeWithTemporaryParent(JsFunctionScope(this.scope, ""), JsRootScope(JsProgram("<js code>")))
    return parse(jsCode, ThrowExceptionOnErrorReporter, scope)
}

private fun extractStringValue(expression: JsirExpression) = when (expression) {
    is JsirExpression.Constant -> {
        val value = expression.value
        when (value) {
            is String -> value
            else -> null
        }
    }
    else -> null
}
