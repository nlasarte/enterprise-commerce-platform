package com.enterprisecommerce.customer;

import java.util.UUID;

record CustomerResponse(UUID id, String firstName, String lastName, String email) {

    static CustomerResponse from(Customer customer) {
        return new CustomerResponse(customer.getId(), customer.getFirstName(), customer.getLastName(),
                customer.getEmail());
    }
}
