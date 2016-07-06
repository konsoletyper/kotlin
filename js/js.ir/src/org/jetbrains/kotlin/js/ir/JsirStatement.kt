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
import org.jetbrains.kotlin.descriptors.FunctionDescriptor

sealed class JsirStatement {
    var source: PsiElement? = null

    class Assignment(var left: JsirExpression?, var right: JsirExpression) : JsirStatement()

    class If(var condition: JsirExpression) : JsirStatement() {
        val thenBody = mutableListOf<JsirStatement>()
    }

    class While(var condition: JsirExpression) : JsirStatement(), JsirLabeled {
        val body = mutableListOf<JsirStatement>()

        override var suggestedLabelName: String? = null
    }

    class DoWhile(var condition: JsirExpression) : JsirStatement(), JsirLabeled {
        val body = mutableListOf<JsirStatement>()

        override var suggestedLabelName: String? = null
    }

    class For(var condition: JsirExpression) : JsirStatement(), JsirLabeled {
        val preAssignments = mutableListOf<Assignment>()
        val postAssignments = mutableListOf<Assignment>()
        val body = mutableListOf<JsirStatement>()

        override var suggestedLabelName: String? = null
    }

    class Return(var value: JsirExpression? = null, var target: FunctionDescriptor) : JsirStatement()

    class Throw(var exception: JsirExpression) : JsirStatement()

    class Break(var target: JsirLabeled) : JsirStatement()

    class Continue(var target: JsirLabeled) : JsirStatement()

    class Block : JsirStatement(), JsirLabeled {
        val body = mutableListOf<JsirStatement>()

        override var suggestedLabelName: String? = null
    }

    class Try() : JsirStatement() {
        val body = mutableListOf<JsirStatement>()
        val catchClauses = mutableListOf<Catch>()
        val finallyClause = mutableListOf<JsirStatement>()
    }

    class Catch(var catchVariable: JsirVariable, var exceptionType: ClassDescriptor? = null) {
        val body = mutableListOf<JsirStatement>()
    }

    class Switch(var selector: JsirExpression) : JsirStatement(), JsirLabeled {
        val clauses = mutableListOf<Case>()
        val defaultClause = mutableListOf<JsirExpression>()

        override var suggestedLabelName: String? = null
    }

    class Case(var value: JsirExpression) {
        val body = mutableListOf<JsirStatement>()
    }
}