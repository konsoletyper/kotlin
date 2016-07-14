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

import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.js.translate.utils.JsAstUtils

fun JsirRenderingContext.kotlinName() = getInternalName(module.builtIns.builtInsModule)

fun JsirRenderingContext.kotlinReference(name: String) = JsAstUtils.pureFqn(name, JsAstUtils.pureFqn(kotlinName(), null))

val ModuleDescriptor.importName: String
        get() = name.asString().let { it.substring(1, it.length - 1) }
