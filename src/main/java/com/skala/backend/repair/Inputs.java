package com.skala.backend.repair;

import jakarta.validation.constraints.*;
import jakarta.validation.Valid;
import java.util.*;

public final class Inputs {
    public record Intake(@NotNull Long productId,@NotBlank @Size(max=10000) String symptoms,
        @NotNull @Size(max=50) List<@NotBlank @Size(max=100) String> errorCodes,@NotBlank @Size(max=100) String firmware,
        @NotNull Map<String,Object> userInput) {}
    public record Resume(@NotNull Long requestId,@NotNull UUID submissionKey,@NotEmpty List<@NotNull Long> attachmentIds) {}
    public record PhotoSubmission(@NotNull UUID submissionKey,@NotEmpty List<@NotNull Long> attachmentIds) {}
    public record Fault(@NotBlank @Pattern(regexp="PCB|MOTOR|SW|OTHER") String area,Long componentId,Long partId) {}
    public record ReportContent(@NotBlank @Size(max=10000) String actualCause,@NotNull @Valid List<Fault> faults,
        @NotBlank @Size(max=10000) String actualAction,@NotNull @Size(max=10000) String technicianOpinion,@NotNull Boolean repairSuccessful,
        @NotNull Acceptance aiAcceptance,Outcome outcome) {
        public ReportContent(String cause,List<Fault> faults,String action,String opinion,Boolean successful,Acceptance assessment) {this(cause,faults,action,opinion,successful,assessment,null);}
    }
    public enum Outcome { FIXED, NOT_FIXED, NO_FAULT_FOUND }
    public enum Acceptance { ACCEPTED, PARTIAL, REJECTED, NOT_AVAILABLE }
    public record Draft(@Min(0) long version,@NotNull @Valid ReportContent content) {}
    public record Confirm(@Min(0) long version,@Min(1) int revision) {}
    public record Comment(@Min(1) int revision,@NotBlank @Size(max=4000) String body,@NotNull UUID requestKey) {}
    public enum Source { WAREHOUSE, FACTORY }
    public record Item(@NotNull Long partId,@Min(1) int quantity,@NotNull Source source,Long warehouseId,Long factoryId) {}
    public record Proposal(@NotEmpty @Valid List<Item> items) {}
    public record Decision(@NotBlank @Size(max=2000) String reason) {}
}
