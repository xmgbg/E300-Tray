package com.ezhan.amr.viewmodels;

import android.app.Service;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;

public class ViewModelProviderFactory implements ViewModelProvider.Factory {
    private final Service service;

    public ViewModelProviderFactory(Service service) {
        this.service = service;
    }

    @SuppressWarnings("unchecked")
    @NonNull
    @Override
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
        if (modelClass.isAssignableFrom(CruiseViewModel.class)) {
            return (T) new CruiseViewModel(service.getApplication());
        }
        throw new IllegalArgumentException("Unknown ViewModel class");
    }
}