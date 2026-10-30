package com.tesa.foodbridge.dashboard.service;

import com.tesa.foodbridge.dashboard.api.AdminDashboardResponse;
import com.tesa.foodbridge.dashboard.repository.AdminDashboardRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminDashboardService {

    private final AdminDashboardRepository repository;

    public AdminDashboardService(
            AdminDashboardRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public AdminDashboardResponse getDashboard() {
        return repository.getStatistics();
    }
}