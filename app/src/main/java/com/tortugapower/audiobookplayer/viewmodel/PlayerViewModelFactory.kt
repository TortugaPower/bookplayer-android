package com.tortugapower.audiobookplayer.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.tortugapower.audiobookplayer.repository.LibraryRepository

class PlayerViewModelFactory(
    private val appContext: Context,
    private val repository: LibraryRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PlayerViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return PlayerViewModel(appContext, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
