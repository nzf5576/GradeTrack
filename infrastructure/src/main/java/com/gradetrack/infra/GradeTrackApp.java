package com.gradetrack.infra;

import software.amazon.awscdk.App;
import software.amazon.awscdk.Environment;
import software.amazon.awscdk.StackProps;

public final class GradeTrackApp {

    private GradeTrackApp() {
    }

    public static void main(final String[] args) {
        App app = new App();

        Environment env = Environment.builder()
                .account(System.getenv("CDK_DEFAULT_ACCOUNT"))
                .region(System.getenv().getOrDefault("CDK_DEFAULT_REGION", "us-east-1"))
                .build();

        new GradeTrackDevStack(app, "GradeTrackDevStack", StackProps.builder()
                .env(env)
                .description("GradeTrack dev environment: free-tier RDS Postgres, auth Lambda, API Gateway")
                .build());

        app.synth();
    }
}
