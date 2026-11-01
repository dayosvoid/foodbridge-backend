package com.tesa.foodbridge.listing.service;

import com.tesa.foodbridge.activity.api.ActivityRecorder;
import com.tesa.foodbridge.listing.api.ListingOperations.ListingView;
import com.tesa.foodbridge.listing.repository.ListingRepository;
import com.tesa.foodbridge.listing.service.DefaultListingOperations;
import com.tesa.foodbridge.shared.*;
import com.tesa.foodbridge.user.api.UserDirectory;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ListingDeletionTest {
    final ListingRepository repository=mock(ListingRepository.class);
    final UserDirectory users=mock(UserDirectory.class);
    final ActivityRecorder activity=mock(ActivityRecorder.class);
    final DefaultListingOperations service=new DefaultListingOperations(repository,users,activity);
    final Actor owner=new Actor(UUID.randomUUID(),Actor.Role.DONOR);
    final UUID id=UUID.randomUUID();
    void listing(String status) {
        when(repository.findLocked(id)).thenReturn(List.of(new ListingView(id,owner.id(),"Owner",
                "Food","Description","OTHER",java.math.BigDecimal.ONE,"ITEMS",null,"Lagos",
                java.time.Instant.now(),status,java.time.Instant.now(),java.time.Instant.now(),0)));
    }
    @Test void recordsBeforeChangingStatus() {
        listing("AVAILABLE");
        when(repository.archiveDetails(id)).thenReturn("{\"previous_status\":\"AVAILABLE\"}");
        service.delete(owner,id);
        var order=inOrder(users,repository,activity);
        order.verify(users).requireRole(owner.id(),owner.role());
        order.verify(repository).findLocked(id);
        order.verify(repository).archiveDetails(id);
        order.verify(activity).record(owner.id(),id,"LISTING_ARCHIVED","{\"previous_status\":\"AVAILABLE\"}");
        order.verify(repository).markDeleted(id);
    }
    @Test void repeatedDeletionDoesNotWrite() {
        listing("DELETED"); service.delete(owner,id);
        verifyNoInteractions(activity); verify(repository,never()).markDeleted(any());
    }
    @Test void forbiddenDeletionDoesNotWrite() {
        listing("AVAILABLE");
        assertEquals("FORBIDDEN",assertThrows(RuleViolation.class,()->service.delete(new Actor(UUID.randomUUID(),Actor.Role.DONOR),id)).code());
        verifyNoInteractions(activity); verify(repository,never()).markDeleted(any());
    }
    @Test void missingListingDoesNotWrite() {
        assertEquals("LISTING_NOT_FOUND",assertThrows(RuleViolation.class,()->service.delete(owner,id)).code());
        verifyNoInteractions(activity);
    }
}
