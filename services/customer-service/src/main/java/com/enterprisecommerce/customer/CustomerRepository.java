package com.enterprisecommerce.customer;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CustomerRepository extends JpaRepository<Customer, UUID> {

    @Modifying
    @Query("delete from Customer customer where customer.id = :id")
    int deleteCustomer(@Param("id") UUID id);
}
