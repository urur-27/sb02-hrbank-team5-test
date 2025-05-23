package com.hrbank.service.basic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hrbank.dto.backup.BackupDto;
import com.hrbank.dto.backup.CursorPageResponseBackupDto;
import com.hrbank.entity.Backup;
import com.hrbank.entity.BinaryContent;
import com.hrbank.enums.BackupStatus;
import com.hrbank.exception.ErrorCode;
import com.hrbank.exception.RestException;
import com.hrbank.generator.EmployeeCsvGenerator;
import com.hrbank.mapper.BackupMapper;
import com.hrbank.repository.BackupRepository;
import com.hrbank.repository.BinaryContentRepository;
import com.hrbank.repository.EmployeeChangeLogRepository;
import com.hrbank.repository.EmployeeRepository;
import com.hrbank.repository.specification.BackupSpecifications;
import com.hrbank.service.BackupService;
import com.hrbank.storage.BinaryContentStorage;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


@Service
@RequiredArgsConstructor
@Slf4j
public class BasicBackupService implements BackupService {

  private final BackupRepository backupRepository;
  private final BackupMapper backupMapper;
  private final EmployeeChangeLogRepository employeeChangeLogRepository;
  private final EmployeeRepository employeeRepository;
  private final BinaryContentRepository binaryContentRepository;
  private final BinaryContentStorage binaryContentStorage;
  private final EmployeeCsvGenerator employeeCsvGenerator;


  @Transactional(readOnly = true)
  @Override
  public CursorPageResponseBackupDto searchBackups(
      String worker, BackupStatus status, OffsetDateTime from, OffsetDateTime to,
      Long idAfter, String cursor, Integer size, String sortField, String sortDirection) {

    int pageSize = size;

    if(cursor != null) {
      idAfter = decodeCursor(cursor);
    }

    // 기본 정렬 순서 설정
    Sort.Direction direction = Sort.Direction.fromString(sortDirection);

    // 정렬 설정 (기본 정렬 + id 정렬)
    Sort sort = Sort.by(direction, sortField).and(Sort.by(Sort.Direction.ASC, "id"));

    // Pageable 설정 처음 로딩 페이지 의미
    Pageable pageable = PageRequest.of(0, pageSize + 1, sort);

    // 검색 조건 조립
    Specification<Backup> spec = BackupSpecifications.buildSearchSpecification(
        worker, status, from, to, idAfter, sortDirection
    );

    // 쿼리 실행
    List<Backup> backups = backupRepository.findAll(spec, pageable).getContent();

    // Dto 변환
    List<BackupDto> backupDtos = backups.stream()
        .map(backupMapper::toDto)
        .collect(Collectors.toList());

    boolean hasNext = backupDtos.size() > pageSize;
    if (hasNext) {
      backupDtos.remove(pageSize); // 초과된 한 개 삭제
    }

    Long nextIdAfter = hasNext ? backups.get(pageSize - 1).getId() : null;
    String nextCursor = hasNext ? encodeCursor(nextIdAfter) : null;

    // (전체 개수 필요 없으면 이거 생략 가능)
    long totalElements = backupRepository.count(
        BackupSpecifications.buildSearchSpecification(worker, status, from, to, null, sortDirection)
    );


    return new CursorPageResponseBackupDto(
        backupDtos,
        nextCursor,
        nextIdAfter,
        size,
        totalElements,
        hasNext
    );
  }

  @Transactional(readOnly = true)
  @Override
  public BackupDto findLatestBackupByStatus(BackupStatus status) {
    return backupRepository.findTopByStatusOrderByEndedAtDesc(status)
        .map(backupMapper::toDto)
        .orElse(null);
  }


  @Transactional
  @Override
  public BackupDto runBackup(String requesterIp) {

    // 실행중인 백업 유무 확인
    if (backupRepository.existsByStatus(BackupStatus.IN_PROGRESS)) {
      throw new RestException(ErrorCode.BACKUP_ALREADY_IN_PROGRESS);
    }

    if (!isBackupRequired()) {
      // 백업 필요 없으면 SKIPPED 처리
      Backup skipped = Backup.builder()
          .worker(requesterIp)
          .status(BackupStatus.SKIPPED)
          .startedAt(OffsetDateTime.now())
          .endedAt(OffsetDateTime.now())
          .build();
      backupRepository.save(skipped);
      return backupMapper.toDto(skipped);
    }

    // 백업 이력 생성
    BackupDto inProgress = createInProgressBackup(requesterIp);

    try {
      // 백업 파일 생성
      Long fileId = generateBackupFile(inProgress);

      // 성공 처리
      markBackupCompleted(inProgress.id(), fileId);


    } catch (Exception e) {
      // 로그 파일 생성
      Long logFileId = saveErrorLogFile(inProgress, e);

      // 실패 처리
      markBackupFailed(inProgress.id(), logFileId);
    }

    return inProgress;
  }


  private boolean isBackupRequired() {
    // 직원이 없으면 백업할 필요 X
    if(!employeeRepository.existsBy()){
      return false;
    }

    Optional<Backup> lastCompletedBackup = backupRepository.findTopByStatusOrderByEndedAtDesc(
        BackupStatus.COMPLETED);

    if(lastCompletedBackup.isEmpty()){
      return true;
    }

    OffsetDateTime lastBackupTime = lastCompletedBackup.get().getEndedAt();

    // 특정 시간 이후 직원 업데이트 내역 확인
    return employeeChangeLogRepository.existsByAtAfter(lastBackupTime);
  }

  private BackupDto createInProgressBackup(String requesterIp) {
    Backup backup = Backup.builder()
        .worker(requesterIp)
        .status(BackupStatus.IN_PROGRESS)
        .startedAt(OffsetDateTime.now())
        .build();

    Backup saved = backupRepository.save(backup);
    return backupMapper.toDto(saved);
  }

  private void markBackupCompleted(Long backupId, Long fileId) {
    Backup backup = backupRepository.findById(backupId)
        .orElseThrow(() -> new RestException(ErrorCode.BACKUP_NOT_FOUND));

    BinaryContent file = binaryContentRepository.findById(fileId)
        .orElseThrow(() -> new RestException(ErrorCode.BACKUP_CSV_NOT_FOUND));

    backup.completeBackup(file);
  }

  private void markBackupFailed(Long backupId, Long logFileId) {
    Backup backup = backupRepository.findById(backupId)
        .orElseThrow(() -> new RestException(ErrorCode.BACKUP_NOT_FOUND));

    BinaryContent logFile = binaryContentRepository.findById(logFileId)
        .orElseThrow(() -> new RestException(ErrorCode.BACKUP_ERROR_LOG_NOT_FOUND));

    backup.failBackup(logFile);
  }

  private Long decodeCursor(String cursor) {
    try {
      byte[] decodedBytes = Base64.getDecoder().decode(cursor);
      String json = new String(decodedBytes, StandardCharsets.UTF_8);

      // JSON에서 "id" 필드 추출
      ObjectMapper objectMapper = new ObjectMapper();
      JsonNode node = objectMapper.readTree(json);
      return node.has("id") ? node.get("id").asLong() : null;
    } catch (Exception e) {
      log.warn("Invalid cursor: {}", cursor);
      throw new IllegalArgumentException("Invalid cursor format: " + cursor, e);
    }
  }

  private String encodeCursor(Long id) {
    if (id == null) return null;
    String json = "{\"id\":" + id + "}";
    return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
  }

  private Long generateBackupFile(BackupDto dto) {
    File tempCsv = null;
    BinaryContent binaryContent = new BinaryContent();
    binaryContent = binaryContentRepository.save(binaryContent);
    Long contentId = binaryContent.getId();

    try{
      tempCsv = employeeCsvGenerator.generate(dto);
      binaryContentStorage.putCsvFile(contentId, tempCsv);
      // 임시 파일 생성때의 이름을 쓰면 불필요한 랜덤값이 붙으니, 자체적으로 파일이름 생성.
      DateTimeFormatter fmt = DateTimeFormatter
          .ofPattern("yyyyMMdd_HHmmss");
      String timestamp = fmt.format(dto.startedAt());
      String backupId = String.valueOf(dto.id());
      String fileName = "employee_backup_" + backupId + "_" + timestamp + ".csv";

      binaryContent.setFileName(fileName);
      binaryContent.setContentType("text/csv");
      binaryContent.setSize(tempCsv.length());
      binaryContentRepository.save(binaryContent);

      return contentId;
    } catch (Exception e) {
      try{
        binaryContentStorage.deleteCsvFile(contentId);
      } catch (IOException ex) {
        log.warn("CSV 파일 삭제 실패 (id={}): {}", contentId, ex.getMessage());
      }
      binaryContentRepository.deleteById(contentId);
      throw new RuntimeException("직원 CSV 파일 생성 중 오류 발생", e);
    } finally {
      if (tempCsv != null && tempCsv.exists()) { // 만들었던 임시 파일을 삭제해주기
        if (!tempCsv.delete()) {
          log.warn("임시 파일 삭제 실패: {}", tempCsv.getAbsolutePath());
        }
      }
    }
  }

  private Long saveErrorLogFile(BackupDto dto, Exception e) {
    StringWriter sw = new StringWriter();
    e.printStackTrace(new PrintWriter(sw));
    String trace = sw.toString();

    BinaryContent binaryContent = new BinaryContent();
    DateTimeFormatter fmt = DateTimeFormatter
        .ofPattern("yyyyMMdd_HHmmss");
    String timestamp = fmt.format(dto.startedAt());
    String backupId = String.valueOf(dto.id());

    binaryContent.setFileName("backup_error_" + backupId + "_" + timestamp + ".log");
    binaryContent.setContentType("text/plain");
    binaryContent = binaryContentRepository.save(binaryContent);
    Long contentId = binaryContent.getId();

    try{
      Long size = binaryContentStorage.putErrorLog(contentId, trace);
      binaryContent.setSize(size);
    }catch (IOException ex) {
      throw new RestException(ErrorCode.FILE_WRITE_ERROR);
    }
    return contentId;
  }

}


