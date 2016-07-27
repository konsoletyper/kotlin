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

package org.jetbrains.kotlin.js.ir.transform

import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.js.ir.*
import org.jetbrains.kotlin.resolve.DescriptorUtils

class ClassClosureTransformation {
    fun apply(module: JsirModule) {
        val closureFields = mutableMapOf<ClassDescriptor, List<JsirExpression>>()
        for (cls in module.classes.values) {
            val descriptor = cls.descriptor
            val container = descriptor.containingDeclaration
            if (container !is FunctionDescriptor) continue
            val containingClass = DescriptorUtils.getParentOfType(container, ClassDescriptor::class.java)

            val transformation = SingleClassClosureTransformation(module, cls, module.classes[containingClass])
            transformation.apply(cls)
            transformation.addClosureFields()
            closureFields[descriptor] = transformation.closureExpressions
        }
        applyToCallSites(closureFields, module)
    }

    fun applyToCallSites(closureFields: Map<ClassDescriptor, List<JsirExpression>>, module: JsirModule) {
        for (function in (module.topLevelFunctions + module.classes.values.flatMap { it.functions.values })) {
            applyToCallSites(closureFields, function.parameters.flatMap { it.defaultBody })
            applyToCallSites(closureFields, function.body)
        }
        for (file in module.files) {
            applyToCallSites(closureFields, file.initializerBody)
        }
    }

    fun applyToCallSites(closureFields: Map<ClassDescriptor, List<JsirExpression>>, statements: List<JsirStatement>) {
        statements.visit(object : JsirVisitor<Unit, Unit> {
            override fun accept(statement: JsirStatement, inner: () -> Unit) = inner()

            override fun accept(expression: JsirExpression, inner: () -> Unit) {
                when (expression) {
                    is JsirExpression.Invocation -> applyToCallSite(closureFields, expression.function, expression.arguments)
                    is JsirExpression.NewInstance -> applyToCallSite(closureFields, expression.constructor, expression.arguments)
                }
                inner()
            }
        })
    }

    private fun applyToCallSite(
            closureFields: Map<ClassDescriptor, List<JsirExpression>>,
            function: FunctionDescriptor, arguments: MutableList<JsirExpression>
    ) {
        if (function !is ConstructorDescriptor) return
        arguments.addAll(0, closureFields[function.containingDeclaration].orEmpty())
    }
}

private class SingleClassClosureTransformation(val module: JsirModule, val root: JsirClass, val container: JsirClass?) : JsirMapper {
    private var currentClass: JsirClass = root
    private var currentFunction: JsirFunction? = null
    private var currentVariableContainer: JsirVariableContainer? = null
    private var thisClosureField: JsirField.Closure? = null
    val closureFields = mutableListOf<JsirField.Closure>()
    val closureExpressions = mutableListOf<JsirExpression>()
    val closureFieldsByVariables = mutableMapOf<JsirVariable, JsirField.Closure>()

    fun apply(cls: JsirClass) {
        currentClass = cls
        currentFunction = null
        for (function in cls.functions.values) {
            currentFunction = function
            currentVariableContainer = function.variableContainer
            function.parameters.forEach { it.defaultBody.replace(this) }
            function.body.replace(this)
        }
        for (innerClass in cls.innerClasses) {
            apply(innerClass)
        }
    }

    fun addClosureFields() {
        if (closureFields.isEmpty()) return

        root.closureFields += closureFields
        for (constructorDescriptor in root.descriptor.constructors) {
            val constructor = root.functions[constructorDescriptor] ?: continue
            val statements = mutableListOf<JsirStatement>()
            val parameters = mutableListOf<JsirParameter>()
            for (closureField in closureFields) {
                val parameter = constructor.variableContainer.createVariable(false, closureField.suggestedName)
                val reference = JsirExpression.FieldAccess(JsirExpression.This(), closureField)
                statements += JsirStatement.Assignment(reference, parameter.makeReference())
                parameters += JsirParameter(parameter)
            }

            constructor.parameters.addAll(0, parameters)
            constructor.body.addAll(0, statements)
        }
    }

    override fun map(statement: JsirStatement, canChangeType: Boolean): JsirStatement = statement

    override fun map(expression: JsirExpression): JsirExpression {
        return when {
            expression is JsirExpression.VariableReference && expression.variable.container != currentVariableContainer -> {
                val field = closureFieldsByVariables.getOrPut(expression.variable) {
                    val newField = JsirField.Closure(expression.variable.suggestedName)
                    closureExpressions += expression.variable.makeReference()
                    closureFields += newField
                    newField
                }
                JsirExpression.FieldAccess(getReceiver(), field)
            }
            expression is JsirExpression.ThisCapture -> {
                val knownField = thisClosureField
                val field = if (knownField == null) {
                    val newField = JsirField.Closure("outerThis")
                    thisClosureField = newField
                    closureExpressions += getClosureThis(expression.target)
                    closureFields += newField
                    newField
                }
                else {
                    knownField
                }
                JsirExpression.FieldAccess(getReceiver(), field)
            }
            else -> expression
        }
    }

    private fun getReceiver(): JsirExpression {
        var cls = currentClass.descriptor
        var receiver: JsirExpression = JsirExpression.This()
        while (cls != root.descriptor) {
            receiver = JsirExpression.FieldAccess(receiver, JsirField.OuterClass(cls))
            cls = cls.containingDeclaration as ClassDescriptor
        }
        return receiver
    }

    private fun getClosureThis(target: ClassDescriptor): JsirExpression {
        var cls = container!!.descriptor
        var result: JsirExpression = JsirExpression.This()
        while (cls != target) {
            result = JsirExpression.FieldAccess(result, JsirField.OuterClass(cls))
            cls = cls.containingDeclaration as ClassDescriptor
        }
        return result
    }
}