package io.github.seyud.weave.arch

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import io.github.seyud.weave.core.Info
import io.github.seyud.weave.core.di.ServiceLocator
import io.github.seyud.weave.ui.home.HomeViewModel
import io.github.seyud.weave.ui.install.InstallViewModel
import io.github.seyud.weave.ui.log.LogViewModel
import io.github.seyud.weave.ui.module.ModuleViewModel
import io.github.seyud.weave.ui.module.state.ModulePreferencesRepository
import io.github.seyud.weave.ui.modulerepo.ModuleRepoViewModel
import io.github.seyud.weave.ui.superuser.SuperuserViewModel
import io.github.seyud.weave.ui.surequest.SuRequestViewModel

interface ViewModelHolder : LifecycleOwner, ViewModelStoreOwner {

    val viewModel: BaseViewModel

    fun startObserveLiveData() {
        viewModel.uiEvents.observe(this, this::onUiEventDispatched)
        Info.isConnected.observe(this, viewModel::onNetworkChanged)
    }

    /**
     * Called for all [UiEvent]s published by the associated viewModel.
     */
    fun onUiEventDispatched(event: UiEvent) {}
}

object VMFactory : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when (modelClass) {
            HomeViewModel::class.java -> HomeViewModel(ServiceLocator.networkService)
            LogViewModel::class.java -> LogViewModel(ServiceLocator.logRepo)
            ModuleRepoViewModel::class.java -> ModuleRepoViewModel()
            ModuleViewModel::class.java ->
                ModuleViewModel(ModulePreferencesRepository(ServiceLocator.settingsPrefs))
            SuperuserViewModel::class.java -> SuperuserViewModel(ServiceLocator.policyDB)
            InstallViewModel::class.java ->
                InstallViewModel(ServiceLocator.networkService, ServiceLocator.markwon)
            SuRequestViewModel::class.java ->
                SuRequestViewModel(ServiceLocator.policyDB, ServiceLocator.timeoutPrefs)
            else -> modelClass.getDeclaredConstructor().newInstance()
        } as T
    }
}

inline fun <reified VM : ViewModel> ViewModelHolder.viewModel() =
    lazy(LazyThreadSafetyMode.NONE) {
        ViewModelProvider(this, VMFactory)[VM::class.java]
    }
