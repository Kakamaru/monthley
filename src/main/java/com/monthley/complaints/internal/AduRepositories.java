package com.monthley.complaints.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface AduCategoryRepository extends JpaRepository<AduCategory, Long> {
    List<AduCategory> findBySpCodeOrderBySortOrderAscNameAsc(String spCode);
    Optional<AduCategory> findByIdAndSpCode(Long id, String spCode);
}

interface AduComplaintRepository extends JpaRepository<AduComplaint, Long> {
    Optional<AduComplaint> findByIdAndSpCode(Long id, String spCode);
}

interface AduReplyRepository extends JpaRepository<AduReply, Long> {
}

interface AduSettingRepository extends JpaRepository<AduSetting, String> {
}
