package com.tesa.foodbridge.dashboard.web;

import com.tesa.foodbridge.dashboard.api.AdminDashboardResponse;
import com.tesa.foodbridge.dashboard.service.AdminDashboardService;
import com.tesa.foodbridge.distribution.api.DailyDistributionOperations;
import com.tesa.foodbridge.distribution.api.DailyDistributionSummary;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class AdminDashboardController {

    private final AdminDashboardService service;
    private final DailyDistributionOperations dailyDistribution;

    public AdminDashboardController(
            AdminDashboardService service,
            DailyDistributionOperations dailyDistribution) {

        this.service = service;
        this.dailyDistribution = dailyDistribution;
    }

    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth"
    )
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/dashboard")
    public AdminDashboardResponse getDashboard() {
        return service.getDashboard();
    }

    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth"
    )
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/distribution/today")
    public DailyDistributionSummary getTodayDistribution() {
        return dailyDistribution.getTodaySummary();
    }
}