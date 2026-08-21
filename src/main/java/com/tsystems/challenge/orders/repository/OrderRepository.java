package com.tsystems.challenge.orders.repository;

import com.tsystems.challenge.orders.domain.Order;
import com.tsystems.challenge.orders.domain.OrderStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository {
    Order save(Order order);
    Optional<Order> findById(UUID id);
    List<Order> findAll();

    /** Orders currently in the given status, used by the pricing retry scheduler. */
    List<Order> findByStatus(OrderStatus status);
}
