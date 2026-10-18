import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { jvmToolchain(17) }

dependencies {
    // 零 Android 依赖：纯 Kotlin JVM 库。协程为趟次步进机的运行时底座；
    // serialization-json 供工具参数 JSON 解析 / 勘合规则与清单读写（容错式
    // 使用——坏 JSON 全部折叠降级、绝不击穿循环）。
    api(libs.coroutines.core)
    implementation(libs.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}

// composite build 依赖替换：宿主 includeBuild 时按 group:name 自动替换远端坐标
group = "com.apex.courierkit"
version = "1.0.0"

mavenPublishing {
    configure(KotlinJvm(javadocJar = JavadocJar.None(), sourcesJar = true))
    coordinates(
        groupId = "com.apex.courierkit",
        artifactId = "courier-dispatch",
        // 与上方 project version 同源（CI smoke check 按此 grep 校验 mavenLocal 产物）
        version = project.version.toString(),
    )
    pom {
        name.set("Apex Courier Kit :: Courier Dispatch")
        description.set(
            "Pure-Kotlin dispatch-relay agent-loop engine: inbox steer/queue semantics, " +
                "trip step machine with parallel tool tracks, permit-pattern signal gate, " +
                "structured trip-log compaction and child-station subagents for mobile " +
                "coding workspaces (zero Android deps)"
        )
        url.set("https://github.com/Ultra-Guru/apex-courier-kit")
        licenses {
            license {
                name.set("MIT")
                url.set("https://opensource.org/licenses/MIT")
            }
        }
        developers {
            developer { id.set("AceGuru-mjh") }
        }
        scm {
            connection.set("scm:git:git@github.com:Ultra-Guru/apex-courier-kit.git")
            url.set("https://github.com/Ultra-Guru/apex-courier-kit")
        }
    }
}
