package com.vrcx.android.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.plus

/**
 * Where a screen's derived state is computed.
 *
 * `viewModelScope` is `Dispatchers.Main.immediate`, and `stateIn` collects in the
 * scope it is given — so a combine/filter/sort chain shared there runs on the UI
 * thread on every upstream emission, which for the list screens means the whole
 * list on every keystroke and on every pipeline event. This keeps the job (and
 * therefore the cancellation) of `viewModelScope` and moves only the work.
 */
fun ViewModel.derivationScope(dispatcher: CoroutineDispatcher): CoroutineScope = viewModelScope + dispatcher
