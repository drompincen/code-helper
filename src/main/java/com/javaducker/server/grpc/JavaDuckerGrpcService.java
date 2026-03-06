package com.javaducker.server.grpc;

import com.javaducker.proto.*;
import com.javaducker.server.service.ArtifactService;
import com.javaducker.server.service.SearchService;
import com.javaducker.server.service.StatsService;
import com.javaducker.server.service.UploadService;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import net.devh.boot.grpc.server.service.GrpcService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

@GrpcService
public class JavaDuckerGrpcService extends JavaDuckerGrpc.JavaDuckerImplBase {

    private static final Logger log = LoggerFactory.getLogger(JavaDuckerGrpcService.class);
    private final UploadService uploadService;
    private final ArtifactService artifactService;
    private final SearchService searchService;
    private final StatsService statsService;

    public JavaDuckerGrpcService(UploadService uploadService, ArtifactService artifactService,
                                  SearchService searchService, StatsService statsService) {
        this.uploadService = uploadService;
        this.artifactService = artifactService;
        this.searchService = searchService;
        this.statsService = statsService;
    }

    @Override
    public void health(HealthRequest request, StreamObserver<HealthResponse> responseObserver) {
        responseObserver.onNext(HealthResponse.newBuilder()
                .setStatus("OK")
                .setVersion("2.0.0")
                .build());
        responseObserver.onCompleted();
    }

    @Override
    public void stats(StatsRequest request, StreamObserver<StatsResponse> responseObserver) {
        try {
            Map<String, Object> stats = statsService.getStats();
            StatsResponse.Builder builder = StatsResponse.newBuilder()
                    .setTotalArtifacts((long) stats.get("total_artifacts"))
                    .setIndexedArtifacts((long) stats.get("indexed_artifacts"))
                    .setFailedArtifacts((long) stats.get("failed_artifacts"))
                    .setPendingArtifacts((long) stats.get("pending_artifacts"))
                    .setTotalChunks((long) stats.get("total_chunks"))
                    .setTotalBytes((long) stats.get("total_bytes"));

            @SuppressWarnings("unchecked")
            Map<String, Long> byStatus = (Map<String, Long>) stats.get("artifacts_by_status");
            if (byStatus != null) {
                builder.putAllArtifactsByStatus(byStatus);
            }

            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Stats error", e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void uploadFile(UploadFileRequest request, StreamObserver<UploadFileResponse> responseObserver) {
        try {
            String artifactId = uploadService.upload(
                    request.getFileName(),
                    request.getOriginalClientPath(),
                    request.getMediaType(),
                    request.getSizeBytes(),
                    request.getContent().toByteArray()
            );
            responseObserver.onNext(UploadFileResponse.newBuilder()
                    .setArtifactId(artifactId)
                    .setStatus("STORED_IN_INTAKE")
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Upload error", e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void getArtifactStatus(GetArtifactStatusRequest request,
                                   StreamObserver<GetArtifactStatusResponse> responseObserver) {
        try {
            Map<String, String> status = artifactService.getStatus(request.getArtifactId());
            if (status == null) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("Artifact not found: " + request.getArtifactId())
                        .asRuntimeException());
                return;
            }
            responseObserver.onNext(GetArtifactStatusResponse.newBuilder()
                    .setArtifactId(status.get("artifact_id"))
                    .setFileName(status.get("file_name"))
                    .setStatus(status.get("status"))
                    .setErrorMessage(status.get("error_message"))
                    .setCreatedAt(status.get("created_at"))
                    .setUpdatedAt(status.get("updated_at"))
                    .setIndexedAt(status.get("indexed_at"))
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("GetArtifactStatus error", e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void getArtifactText(GetArtifactTextRequest request,
                                 StreamObserver<GetArtifactTextResponse> responseObserver) {
        try {
            Map<String, String> text = artifactService.getText(request.getArtifactId());
            if (text == null) {
                responseObserver.onError(Status.NOT_FOUND
                        .withDescription("Text not found for artifact: " + request.getArtifactId())
                        .asRuntimeException());
                return;
            }
            responseObserver.onNext(GetArtifactTextResponse.newBuilder()
                    .setArtifactId(text.get("artifact_id"))
                    .setExtractedText(text.get("extracted_text"))
                    .setTextLength(Long.parseLong(text.get("text_length")))
                    .setExtractionMethod(text.get("extraction_method"))
                    .build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("GetArtifactText error", e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }

    @Override
    public void find(FindRequest request, StreamObserver<FindResponse> responseObserver) {
        try {
            List<Map<String, Object>> results;
            int maxResults = request.getMaxResults() > 0 ? request.getMaxResults() : 20;

            switch (request.getMode()) {
                case EXACT -> results = searchService.exactSearch(request.getPhrase(), maxResults);
                case SEMANTIC -> results = searchService.semanticSearch(request.getPhrase(), maxResults);
                default -> results = searchService.hybridSearch(request.getPhrase(), maxResults);
            }

            FindResponse.Builder builder = FindResponse.newBuilder()
                    .setTotalResults(results.size());

            for (Map<String, Object> hit : results) {
                builder.addResults(SearchResult.newBuilder()
                        .setArtifactId((String) hit.get("artifact_id"))
                        .setFileName((String) hit.get("file_name"))
                        .setChunkId((String) hit.get("chunk_id"))
                        .setChunkIndex((int) hit.get("chunk_index"))
                        .setPreview((String) hit.get("preview"))
                        .setScore((double) hit.get("score"))
                        .setMatchType((String) hit.get("match_type"))
                        .build());
            }

            responseObserver.onNext(builder.build());
            responseObserver.onCompleted();
        } catch (Exception e) {
            log.error("Find error", e);
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).asRuntimeException());
        }
    }
}
