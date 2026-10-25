// The portal SPA (React + TypeScript, built with Vite). The app module serves dist/ as static files.
plugins {
    base
}

val npmCi by tasks.registering(Exec::class) {
    description = "Installs the portal's npm dependencies from package-lock.json."
    inputs.files("package.json", "package-lock.json")
    outputs.dir("node_modules")
    commandLine("npm", "ci", "--no-audit", "--no-fund")
}

val buildPortal by tasks.registering(Exec::class) {
    description = "Type-checks and bundles the portal into dist/."
    dependsOn(npmCi)
    inputs.files("package.json", "package-lock.json", "index.html", "tsconfig.json", "vite.config.ts")
    inputs.dir("src")
    inputs.dir("public")
    outputs.dir("dist")
    commandLine("npm", "run", "build")
}

val dist by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(dist.name, layout.projectDirectory.dir("dist")) { builtBy(buildPortal) }
}

tasks.assemble { dependsOn(buildPortal) }
tasks.check { dependsOn(buildPortal) }
tasks.clean { delete("dist") }
