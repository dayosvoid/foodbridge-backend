package com.tesa.foodbridge.reservation.api;

import java.util.List;

public interface ReservationQuery {

    List<FoodRequestResponse> findForListing(String listingId);
}