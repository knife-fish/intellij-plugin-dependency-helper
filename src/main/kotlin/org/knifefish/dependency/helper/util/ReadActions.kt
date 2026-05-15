package org.knifefish.dependency.helper.util

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Computable

internal fun <T> readAction(action: () -> T): T =
    ApplicationManager.getApplication().runReadAction(Computable { action() })
