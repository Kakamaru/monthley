package com.monthley.expenses.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface ExpCategoryRepository extends JpaRepository<ExpCategory, Long> {
    List<ExpCategory> findBySpCodeOrderBySortOrderAscNameAsc(String spCode);
    Optional<ExpCategory> findByIdAndSpCode(Long id, String spCode);
}

interface ExpSupplierRepository extends JpaRepository<ExpSupplier, Long> {
    List<ExpSupplier> findBySpCodeOrderByNameAsc(String spCode);
    Optional<ExpSupplier> findByIdAndSpCode(Long id, String spCode);
}

interface ExpInvoiceRepository extends JpaRepository<ExpInvoice, Long> {
    Optional<ExpInvoice> findByIdAndSpCode(Long id, String spCode);
    boolean existsBySpCodeAndSupplierIdAndInvNo(String spCode, Long supplierId, String invNo);
}

interface ExpInvoiceItemRepository extends JpaRepository<ExpInvoiceItem, Long> {
    List<ExpInvoiceItem> findByInvoiceId(Long invoiceId);
    void deleteByInvoiceId(Long invoiceId);
}

interface ExpPaymentRepository extends JpaRepository<ExpPayment, Long> {
    Optional<ExpPayment> findByIdAndSpCode(Long id, String spCode);
    List<ExpPayment> findByInvoiceIdAndStatus(Long invoiceId, ExpPayment.Status status);
}

interface ExpCashEntryRepository extends JpaRepository<ExpCashEntry, Long> {
    Optional<ExpCashEntry> findByIdAndSpCode(Long id, String spCode);
}

interface ExpSettingRepository extends JpaRepository<ExpSetting, String> {
}

interface ExpPaymentMethodRepository extends JpaRepository<ExpPaymentMethod, Long> {
    List<ExpPaymentMethod> findBySpCodeOrderBySortOrderAscNameAsc(String spCode);
    Optional<ExpPaymentMethod> findByIdAndSpCode(Long id, String spCode);
}
