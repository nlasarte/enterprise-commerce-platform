package com.enterprisecommerce.customer;

import java.util.List;
import java.util.UUID;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class CustomerService {

    private static final String EMAIL_UNIQUE_INDEX = "uq_customers_email";

    private final CustomerRepository repository;

    CustomerService(CustomerRepository repository) {
        this.repository = repository;
    }

    List<CustomerResponse> list() {
        return repository.findAll(Sort.by("id")).stream()
                .map(CustomerResponse::from)
                .toList();
    }

    CustomerResponse get(UUID id) {
        return CustomerResponse.from(findCustomer(id));
    }

    @Transactional
    CustomerResponse create(CustomerRequest request) {
        return save(new Customer(request.firstName(), request.lastName(), request.email()));
    }

    @Transactional
    CustomerResponse update(UUID id, CustomerRequest request) {
        Customer customer = findCustomer(id);
        customer.update(request.firstName(), request.lastName(), request.email());
        return save(customer);
    }

    @Transactional
    void delete(UUID id) {
        if (repository.deleteCustomer(id) == 0) {
            throw new CustomerNotFoundException(id);
        }
    }

    private Customer findCustomer(UUID id) {
        return repository.findById(id).orElseThrow(() -> new CustomerNotFoundException(id));
    }

    private CustomerResponse save(Customer customer) {
        try {
            return CustomerResponse.from(repository.saveAndFlush(customer));
        } catch (DataIntegrityViolationException exception) {
            if (exception.getCause() instanceof ConstraintViolationException violation
                    && EMAIL_UNIQUE_INDEX.equals(violation.getConstraintName())) {
                throw new EmailAlreadyExistsException(exception);
            }
            throw exception;
        }
    }

    static class CustomerNotFoundException extends RuntimeException {

        CustomerNotFoundException(UUID id) {
            super("Customer %s was not found.".formatted(id));
        }
    }

    static class EmailAlreadyExistsException extends RuntimeException {

        EmailAlreadyExistsException(Throwable cause) {
            super("A customer with this email already exists.", cause);
        }
    }
}
