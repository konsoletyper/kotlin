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

package org.jetbrains.kotlin.js.ir.translate

import org.jetbrains.kotlin.js.ir.JsirExpression
import org.jetbrains.kotlin.psi.KtEscapeStringTemplateEntry
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtStringTemplateEntryWithExpression
import org.jetbrains.kotlin.psi.KtVisitorVoid

internal class StringTemplateGenerator(val context: JsirContext) : KtVisitorVoid() {
    private var buffer = StringBuilder()
    private val mutableParts = mutableListOf<JsirExpression>()

    val parts: List<JsirExpression>
        get() = mutableParts

    fun flush() {
        mutableParts += JsirExpression.Constant(buffer.toString())
        buffer.setLength(0)
    }

    override fun visitStringTemplateEntryWithExpression(entry: KtStringTemplateEntryWithExpression) {
        val expression = entry.expression!!
        val translatedExpression = context.generate(expression)
        if (translatedExpression is JsirExpression.Constant && translatedExpression.value is Number) {
            buffer.append(translatedExpression.value.toString())
            return
        }

        val type = context.bindingContext.getType(expression)
        if (type == null || type.isMarkedNullable) {
            flush()
            mutableParts += JsirExpression.ToString(translatedExpression)
        }
        else {
            buffer.append(translatedExpression)
        }
    }

    override fun visitLiteralStringTemplateEntry(entry: KtLiteralStringTemplateEntry) {
        super.visitLiteralStringTemplateEntry(entry)
    }

    override fun visitEscapeStringTemplateEntry(entry: KtEscapeStringTemplateEntry) {
        super.visitEscapeStringTemplateEntry(entry)
    }
}
