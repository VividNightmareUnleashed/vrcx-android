package com.vrcx.android.ui.screen

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.mockito.Answers
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.lang.reflect.WildcardType

/**
 * A mock whose flow-returning members answer with an empty flow instead of null.
 *
 * ViewModels read repository state in their property initialisers, so a plain mock
 * kills them before the constructor returns. Everything else falls back to Mockito's
 * usual defaults.
 */
inline fun <reified T : Any> fakeState(): T = Mockito.mock(T::class.java, EmptyStateAnswer)

object EmptyStateAnswer : Answer<Any?> {
    override fun answer(invocation: InvocationOnMock): Any? =
        emptyValueFor(invocation.method.genericReturnType)
            ?: Answers.RETURNS_DEFAULTS.answer(invocation)
}

private fun emptyValueFor(type: Type?): Any? {
    val raw = rawClassOf(type) ?: return null
    return when {
        Flow::class.java.isAssignableFrom(raw) -> MutableStateFlow(emptyValueFor(firstTypeArgument(type)))
        List::class.java.isAssignableFrom(raw) -> emptyList<Any?>()
        Set::class.java.isAssignableFrom(raw) -> emptySet<Any?>()
        Map::class.java.isAssignableFrom(raw) -> emptyMap<Any?, Any?>()
        raw == String::class.java -> ""
        else -> null
    }
}

private fun rawClassOf(type: Type?): Class<*>? = when (type) {
    is Class<*> -> type
    is ParameterizedType -> type.rawType as? Class<*>
    is WildcardType -> rawClassOf(type.upperBounds.firstOrNull())
    else -> null
}

private fun firstTypeArgument(type: Type?): Type? =
    (type as? ParameterizedType)?.actualTypeArguments?.firstOrNull()
