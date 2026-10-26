package com.tesa.foodbridge.activity.api;
import java.util.UUID;
public interface ActivityRecorder { void record(UUID actorId, UUID listingId, String type); }
