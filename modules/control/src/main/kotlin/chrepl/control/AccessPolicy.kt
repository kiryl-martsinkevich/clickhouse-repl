package chrepl.control

import chrepl.config.model.AccessRule
import chrepl.config.model.Quota
import chrepl.config.model.ReplicatorConfig
import chrepl.config.model.Role

/**
 * RBAC from the config's `access` rules (SPEC §7.4): a principal's roles are those of every rule
 * whose group it belongs to. Submitting needs a requester rule that covers the requested tables
 * (directly or through a dataset) and the target; that rule's quota applies.
 */
class AccessPolicy(
    private val config: ReplicatorConfig,
) {
    fun grants(principal: Principal): Grants = Grants(rules(principal).map { it.role }.toSet())

    private fun rules(principal: Principal): List<AccessRule> = config.access.filter { it.group in principal.groups }

    /** The rule authorising [tables] on [target] for [principal], or a [ForbiddenException]. */
    fun authoriseSubmit(
        principal: Principal,
        dataset: String?,
        tables: List<String>,
        target: String,
    ): AccessRule {
        val candidates = rules(principal).filter { it.role == Role.REQUESTER || it.role == Role.ADMIN }
        if (candidates.isEmpty()) throw ForbiddenException("${principal.user} may not request copies")
        val rule =
            candidates.firstOrNull { rule ->
                target in rule.targets &&
                    tables.all { t -> t in rule.tables || rule.datasets.any { d -> t in datasetTables(d) } } &&
                    (dataset == null || dataset in rule.datasets)
            }
        return rule ?: throw ForbiddenException("${principal.user} may not copy ${dataset ?: tables.joinToString()} to $target")
    }

    /** Datasets, tables and targets visible to [principal] in the catalog. */
    fun catalog(principal: Principal): Catalog {
        val rules = rules(principal)
        // Admins see everything (to inspect any target's history); submitting still needs a requester rule.
        if (rules.any { it.role == Role.ADMIN }) {
            return Catalog(
                datasets = config.datasets.map { CatalogDataset(it.name, it.description, it.tables) },
                tables = config.tables.map { CatalogTable(it.source, it.cobColumn, it.fullCopy) },
                targets = config.targets.keys.toList(),
            )
        }
        val datasets = rules.flatMap { it.datasets }.toSet()
        val tables = rules.flatMap { it.tables }.toSet() + datasets.flatMap { datasetTables(it) }
        return Catalog(
            datasets = config.datasets.filter { it.name in datasets }.map { CatalogDataset(it.name, it.description, it.tables) },
            tables = config.tables.filter { it.source in tables }.map { CatalogTable(it.source, it.cobColumn, it.fullCopy) },
            targets = rules.flatMap { it.targets }.distinct(),
        )
    }

    fun datasetTables(name: String): List<String> = config.datasets.firstOrNull { it.name == name }?.tables ?: emptyList()

    fun quota(rule: AccessRule): Quota? = rule.quota
}

data class Catalog(
    val datasets: List<CatalogDataset>,
    val tables: List<CatalogTable>,
    val targets: List<String>,
)

data class CatalogDataset(
    val name: String,
    val description: String?,
    val tables: List<String>,
)

data class CatalogTable(
    val name: String,
    val cobColumn: String?,
    val fullCopy: Boolean,
)
