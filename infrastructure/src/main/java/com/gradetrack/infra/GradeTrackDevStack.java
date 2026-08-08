package com.gradetrack.infra;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.RemovalPolicy;
import software.amazon.awscdk.SecretValue;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.apigateway.CorsOptions;
import software.amazon.awscdk.services.apigateway.IResource;
import software.amazon.awscdk.services.apigateway.LambdaIntegration;
import software.amazon.awscdk.services.apigateway.ProxyResourceOptions;
import software.amazon.awscdk.services.apigateway.RestApi;
import software.amazon.awscdk.services.ec2.IVpc;
import software.amazon.awscdk.services.ec2.InstanceClass;
import software.amazon.awscdk.services.ec2.InstanceSize;
import software.amazon.awscdk.services.ec2.InstanceType;
import software.amazon.awscdk.services.ec2.Peer;
import software.amazon.awscdk.services.ec2.Port;
import software.amazon.awscdk.services.ec2.SecurityGroup;
import software.amazon.awscdk.services.ec2.SubnetConfiguration;
import software.amazon.awscdk.services.ec2.SubnetSelection;
import software.amazon.awscdk.services.ec2.SubnetType;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.lambda.Code;
import software.amazon.awscdk.services.lambda.Function;
import software.amazon.awscdk.services.lambda.Runtime;
import software.amazon.awscdk.services.rds.Credentials;
import software.amazon.awscdk.services.rds.DatabaseInstance;
import software.amazon.awscdk.services.rds.DatabaseInstanceEngine;
import software.amazon.awscdk.services.rds.PostgresEngineVersion;
import software.amazon.awscdk.services.rds.PostgresInstanceEngineProps;
import software.amazon.awscdk.services.rds.StorageType;
import software.amazon.awscdk.services.cloudfront.BehaviorOptions;
import software.amazon.awscdk.services.cloudfront.Distribution;
import software.amazon.awscdk.services.cloudfront.ErrorResponse;
import software.amazon.awscdk.services.cloudfront.PriceClass;
import software.amazon.awscdk.services.cloudfront.ViewerProtocolPolicy;
import software.amazon.awscdk.services.cloudfront.origins.S3BucketOrigin;
import software.amazon.awscdk.services.s3.Bucket;
import software.amazon.awscdk.services.s3.deployment.BucketDeployment;
import software.amazon.awscdk.services.s3.deployment.Source;
import software.constructs.Construct;

import java.util.List;
import java.util.Map;

/**
 * Dev environment: one free-tier-eligible RDS Postgres instance and one Lambda
 * per bounded context (auth, students, alerts, admin, teacher) behind a shared API Gateway, each mounted
 * at /{context}/{proxy+}. This stack provisions its own minimal VPC (public
 * subnets only, zero NAT Gateways) rather than relying on the account having a
 * default VPC — the account this was first deployed into has none, and
 * CDK-owning the VPC keeps the whole stack reproducible from a clean account.
 * Every Lambda and the database sit in that VPC's public subnets, so there is
 * no NAT Gateway (~$32/mo) anywhere in this stack:
 *  - Lambda -> RDS traffic stays inside the VPC (security-group-to-security-group),
 *    which never needs NAT/internet routing.
 *  - Lambda log delivery to CloudWatch is handled by the Lambda platform itself,
 *    outside the function's own network namespace, so it doesn't need outbound
 *    internet access either.
 *  - The database is also publicly reachable (with its security group locked to
 *    a single developer IP) purely so Flyway can run from a laptop; the Lambda
 *    path never uses that public route.
 */
public class GradeTrackDevStack extends Stack {

    private static final int POSTGRES_PORT = 5432;

    public GradeTrackDevStack(final Construct scope, final String id, final StackProps props) {
        super(scope, id, props);

        String allowedIp = requireContext("allowedIp",
                "Pass the IP address allowed to reach the dev database for migrations: "
                        + "-c allowedIp=<your-ip>/32");
        String dbPassword = requireContext("dbPassword",
                "Pass a database master password: -c dbPassword=<password> "
                        + "(kept out of Secrets Manager to stay fully within free tier)");
        String jwtSecret = requireContext("jwtSecret",
                "Pass a JWT signing secret: -c jwtSecret=<random-string>");

        String dbName = "gradetrack";
        String dbUsername = "gradetrack";

        IVpc vpc = Vpc.Builder.create(this, "Vpc")
                .maxAzs(2)
                .natGateways(0)
                .subnetConfiguration(List.of(
                        SubnetConfiguration.builder()
                                .name("public")
                                .subnetType(SubnetType.PUBLIC)
                                .cidrMask(24)
                                .build()
                ))
                .build();

        SecurityGroup dbSecurityGroup = SecurityGroup.Builder.create(this, "DbSecurityGroup")
                .vpc(vpc)
                .description("GradeTrack dev DB - inbound restricted to the dev machine IP and backend Lambdas")
                .allowAllOutbound(true)
                .build();
        dbSecurityGroup.addIngressRule(Peer.ipv4(allowedIp), Port.tcp(POSTGRES_PORT),
                "Developer laptop access for Flyway migrations");

        DatabaseInstance database = DatabaseInstance.Builder.create(this, "GradeTrackDb")
                .engine(DatabaseInstanceEngine.postgres(PostgresInstanceEngineProps.builder()
                        .version(PostgresEngineVersion.VER_17)
                        .build()))
                .instanceType(InstanceType.of(InstanceClass.BURSTABLE3, InstanceSize.MICRO))
                .vpc(vpc)
                .vpcSubnets(SubnetSelection.builder().subnetType(SubnetType.PUBLIC).build())
                .securityGroups(List.of(dbSecurityGroup))
                .publiclyAccessible(true)
                .allocatedStorage(20)
                .storageType(StorageType.GP2)
                .multiAz(false)
                .databaseName(dbName)
                .credentials(Credentials.fromPassword(dbUsername, SecretValue.unsafePlainText(dbPassword)))
                .storageEncrypted(true) // free with the AWS-managed KMS key, no reason to skip it
                .backupRetention(Duration.days(0))
                .deletionProtection(false)
                .removalPolicy(RemovalPolicy.DESTROY) // dev only: fine to lose this data
                .build();

        Map<String, String> dbEnvironment = Map.of(
                "DB_HOST", database.getDbInstanceEndpointAddress(),
                "DB_PORT", database.getDbInstanceEndpointPort(),
                "DB_NAME", dbName,
                "DB_USER", dbUsername,
                "DB_PASSWORD", dbPassword,
                "JWT_SECRET", jwtSecret
        );

        Function authFunction = backendFunction("AuthFunction",
                "../backend/auth-function/target/auth-function.jar",
                "com.gradetrack.auth.AuthHandler::handleRequest", vpc, dbEnvironment);
        Function studentsFunction = backendFunction("StudentsFunction",
                "../backend/students-function/target/students-function.jar",
                "com.gradetrack.students.StudentsHandler::handleRequest", vpc, dbEnvironment);
        Function alertsFunction = backendFunction("AlertsFunction",
                "../backend/alerts-function/target/alerts-function.jar",
                "com.gradetrack.alerts.AlertsHandler::handleRequest", vpc, dbEnvironment);
        Function adminFunction = backendFunction("AdminFunction",
                "../backend/admin-function/target/admin-function.jar",
                "com.gradetrack.admin.AdminHandler::handleRequest", vpc, dbEnvironment);
        Function teacherFunction = backendFunction("TeacherFunction",
                "../backend/teacher-function/target/teacher-function.jar",
                "com.gradetrack.teacher.TeacherHandler::handleRequest", vpc, dbEnvironment);

        database.getConnections().allowDefaultPortFrom(authFunction, "Auth Lambda access to Postgres");
        database.getConnections().allowDefaultPortFrom(studentsFunction, "Students Lambda access to Postgres");
        database.getConnections().allowDefaultPortFrom(alertsFunction, "Alerts Lambda access to Postgres");
        database.getConnections().allowDefaultPortFrom(adminFunction, "Admin Lambda access to Postgres");
        database.getConnections().allowDefaultPortFrom(teacherFunction, "Teacher Lambda access to Postgres");

        RestApi api = RestApi.Builder.create(this, "GradeTrackApi")
                .description("GradeTrack dev API")
                .defaultCorsPreflightOptions(CorsOptions.builder()
                        .allowOrigins(List.of("*"))
                        .allowHeaders(List.of("Content-Type", "Authorization"))
                        .allowMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"))
                        .build())
                .build();

        mountProxy(api.getRoot(), "auth", authFunction);
        mountProxy(api.getRoot(), "students", studentsFunction);
        mountProxy(api.getRoot(), "alerts", alertsFunction);
        mountProxy(api.getRoot(), "admin", adminFunction);
        mountProxy(api.getRoot(), "teacher", teacherFunction);

        CfnOutput.Builder.create(this, "DbEndpoint")
                .description("RDS endpoint - use as db.url host for Flyway (db/pom.xml -Ddb.url=...)")
                .value(database.getDbInstanceEndpointAddress())
                .build();

        CfnOutput.Builder.create(this, "ApiUrl")
                .description("API Gateway invoke URL - use as VITE_API_URL in frontend/.env")
                .value(api.getUrl())
                .build();

        // Static frontend hosting: private S3 bucket behind CloudFront (Origin Access Control,
        // so the bucket itself stays fully private). Errors are rewritten to /index.html because
        // this is a client-side-routed SPA (BrowserRouter) - a direct load of e.g. /dashboard has
        // no matching S3 key and must fall through to the app shell to let React Router handle it.
        Bucket frontendBucket = Bucket.Builder.create(this, "FrontendBucket")
                .removalPolicy(RemovalPolicy.DESTROY)
                .autoDeleteObjects(true)
                .build();

        Distribution distribution = Distribution.Builder.create(this, "FrontendDistribution")
                .defaultRootObject("index.html")
                .priceClass(PriceClass.PRICE_CLASS_100)
                .defaultBehavior(BehaviorOptions.builder()
                        .origin(S3BucketOrigin.withOriginAccessControl(frontendBucket))
                        .viewerProtocolPolicy(ViewerProtocolPolicy.REDIRECT_TO_HTTPS)
                        .build())
                .errorResponses(List.of(
                        ErrorResponse.builder()
                                .httpStatus(403)
                                .responseHttpStatus(200)
                                .responsePagePath("/index.html")
                                .build(),
                        ErrorResponse.builder()
                                .httpStatus(404)
                                .responseHttpStatus(200)
                                .responsePagePath("/index.html")
                                .build()
                ))
                .build();

        BucketDeployment.Builder.create(this, "FrontendDeployment")
                .sources(List.of(Source.asset("../frontend/dist")))
                .destinationBucket(frontendBucket)
                .distribution(distribution)
                .distributionPaths(List.of("/*"))
                .build();

        CfnOutput.Builder.create(this, "FrontendUrl")
                .description("CloudFront URL for the deployed frontend")
                .value("https://" + distribution.getDistributionDomainName())
                .build();
    }

    private String requireContext(String key, String hint) {
        Object value = this.getNode().tryGetContext(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("Missing required context value '" + key + "'. " + hint);
        }
        return value.toString();
    }

    /** Every backend Lambda shares this shape: Java 21, 512MB, same VPC placement, same env vars. */
    private Function backendFunction(String id, String jarPath, String handler, IVpc vpc,
                                      Map<String, String> environment) {
        return Function.Builder.create(this, id)
                .runtime(Runtime.JAVA_21)
                .handler(handler)
                .code(Code.fromAsset(jarPath))
                .memorySize(512)
                .timeout(Duration.seconds(15))
                .vpc(vpc)
                .vpcSubnets(SubnetSelection.builder().subnetType(SubnetType.PUBLIC).build())
                // This Lambda only ever talks to RDS over the VPC's local network path (see
                // class-level note) and never needs outbound internet access, so it's safe to
                // acknowledge CDK's public-subnet guardrail here instead of paying for a NAT Gateway.
                .allowPublicSubnet(true)
                .environment(environment)
                .build();
    }

    /**
     * Mounts both /{pathPart} and /{pathPart}/{proxy+} -> ANY -> the given Lambda.
     * {proxy+} alone only catches paths with at least one segment after it (e.g.
     * /students/abc), so the bare parent path (e.g. GET /students) needs its own
     * method too, or API Gateway has nothing configured there at all.
     */
    private void mountProxy(IResource root, String pathPart, Function function) {
        LambdaIntegration integration = new LambdaIntegration(function);
        IResource resource = root.addResource(pathPart);
        resource.addMethod("ANY", integration);
        resource.addProxy(ProxyResourceOptions.builder()
                .anyMethod(true)
                .defaultIntegration(integration)
                .build());
    }
}
