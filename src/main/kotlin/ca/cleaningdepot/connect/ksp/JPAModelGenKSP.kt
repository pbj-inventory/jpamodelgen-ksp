package ca.cleaningdepot.connect.ksp

import com.google.devtools.ksp.getClassDeclarationByName
import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.isOpen
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.*
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.toTypeName
import com.squareup.kotlinpoet.ksp.writeTo
import jakarta.persistence.*
import jakarta.persistence.criteria.*
import jakarta.persistence.metamodel.*
import kotlin.reflect.KClass

class JPAModelGenKSP(private val environment: SymbolProcessorEnvironment) : SymbolProcessor {
    private lateinit var map: KSClassDeclaration
    private lateinit var collection: KSClassDeclaration
    private lateinit var set: KSClassDeclaration
    private lateinit var list: KSClassDeclaration

    private var classMap = mutableMapOf<ClassName, FileSpec>()
    private val abstractProperties = mutableMapOf<String, ClassName>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val unresolvable = arrayListOf<KSAnnotated>()
        classMap = mutableMapOf()
        this.map = resolver.getClassDeclarationByName(Map::class.qualifiedName!!)!!
        this.collection = resolver.getClassDeclarationByName(Collection::class.qualifiedName!!)!!
        this.set = resolver.getClassDeclarationByName(Set::class.qualifiedName!!)!!
        this.list = resolver.getClassDeclarationByName(List::class.qualifiedName!!)!!
        val entities = sequence {
            yieldAll(resolver.getSymbolsWithAnnotation("jakarta.persistence.MappedSuperclass"))
            yieldAll(resolver.getSymbolsWithAnnotation("jakarta.persistence.Entity"))
        }
        for (entity in entities) {
            try {
                buildObject(entity as KSClassDeclaration)
            } catch (e: IllegalArgumentException) {
                if (e.message?.endsWith("is not resolvable in the current round of processing.") == true) {
                    unresolvable.add(entity)
                } else {
                    throw e
                }
            }
        }
        for (fileSpec in classMap.values) {
            fileSpec.writeTo(environment.codeGenerator, Dependencies.ALL_FILES)
        }
        return unresolvable
    }

    private fun buildObject(clazz: KSClassDeclaration) {
        val originalClassName = clazz.toClassName()
        val generatedClassName = ClassName(originalClassName.packageName, originalClassName.simpleName + "_")
        val file = FileSpec.builder(generatedClassName)
        val type = TypeSpec.objectBuilder(generatedClassName).addAnnotation(
            AnnotationSpec.builder(ClassName("jakarta.persistence.metamodel", "StaticMetamodel"))
                .addMember("%L::class", originalClassName.canonicalName)
                .build()
        ).addAnnotation(ClassName("org.springframework.aot.hint.annotation", "Reflective"))
        val properties = mutableListOf<PropertySpec>()
        val functions = mutableListOf<PropertySpec>()
        val joinFunctions = mutableListOf<FunSpec>()
        for (property in clazz.getAllProperties().distinctBy { it.simpleName.asString() }) {
            val propertyType = property.type.resolve()
            val deprecatedAnnotation = property.getAnnotation(Deprecated::class)
            val deprecatedAnnotations = deprecatedAnnotation?.let {
                listOf(
                    AnnotationSpec.builder(Deprecated::class.asClassName())
                        .addMember("%S", deprecatedAnnotation.arguments.firstOrNull()?.value ?: "").build()
                )
            } ?: emptyList()
            properties.add(
                PropertySpec.builder(
                    camelToShoutCase(property.simpleName.asString()), String::class.asClassName(), KModifier.CONST
                ).initializer("%S", property.simpleName.asString()).addAnnotations(deprecatedAnnotations).build()
            )
            val parentClass = property.parentDeclaration as? KSClassDeclaration
            if (clazz.qualifiedName != parentClass?.qualifiedName) continue
            if (property.origin == Origin.KOTLIN_LIB || property.origin == Origin.JAVA_LIB) continue
            if (property.isAbstract()) {
                abstractProperties[propertyType.toClassName().canonicalName + " " + property.simpleName.asString()] =
                    originalClassName
                continue
            }
            val attributeType =
                if (property.getAnnotation(Convert::class) == null) getAttributeType(propertyType) else AttributeType.SINGULAR
            properties.add(
                PropertySpec.builder(
                    property.simpleName.asString(),
                    attributeType.getAttribute(clazz.asStarProjectedType().toTypeName(), propertyType),
                    KModifier.LATEINIT
                )
                    .addKdoc("@see %L.%L", originalClassName.canonicalName, property.simpleName.asString())
                    .addAnnotation(Volatile::class)
                    .addAnnotation(ClassName("org.springframework.aot.hint.annotation", "Reflective"))
                    .addAnnotations(deprecatedAnnotations)
                    .mutable()
                    .build()
            )
            val typeVariable = TypeVariableName.invoke("T", clazz.asStarProjectedType().toTypeName())
            val shouldBeGeneric = clazz.isOpen() && attributeType == AttributeType.SINGULAR
            functions.add(
                PropertySpec.builder(property.simpleName.asString() + "_", attributeType.getType(propertyType))
                    .addTypeVariables(if (shouldBeGeneric) listOf(typeVariable) else listOf())
                    .receiver(
                        Path::class.asClassName()
                            .parameterizedBy(if (shouldBeGeneric) typeVariable else originalClassName)
                    )
                    .getter(
                        FunSpec.getterBuilder()
                            .addCode("return this.get(%L.%L)", generatedClassName, property.simpleName.asString())
                            .build()
                    )
                    .addAnnotations(deprecatedAnnotations)
                    .build()
            )
            if (property.isJoinable()) {
                val joinType = ParameterSpec.builder("joinType", JoinType::class)
                    .defaultValue("%L.%L", JoinType::class.qualifiedName, JoinType.INNER)
                    .build()
                val returnType =
                    attributeType.getJoin(if (clazz.isOpen()) typeVariable else originalClassName, propertyType)
                val joinFun = FunSpec.builder(property.simpleName.asString() + "_")
                    .returns(returnType)
                    .addTypeVariables(if (clazz.isOpen()) listOf(typeVariable) else listOf())
                    .addParameter(joinType)
                    .receiver(
                        From::class.asClassName()
                            .parameterizedBy(STAR, if (clazz.isOpen()) typeVariable else originalClassName)
                    )
                    .addAnnotations(deprecatedAnnotations)
                    .addCode(
                        "return this.join(%L.%L, %L)", generatedClassName, property.simpleName.asString(), joinType.name
                    )
                joinFunctions.add(joinFun.build())
            }
            if (property.modifiers.contains(Modifier.OVERRIDE)) {
                // This is some jank for properties that get overridden. The abstract class references the subclass
                if (propertyType.arguments.isNotEmpty()) continue
                val declaringClassName =
                    abstractProperties.remove(propertyType.toClassName().canonicalName + " " + property.simpleName.asString())
                        ?: continue
                val declaringClass = classMap[declaringClassName]?.toBuilder() ?: continue
                val typeSpec = (declaringClass.members.removeFirst() as TypeSpec).toBuilder()
                val abstractAttributeType =
                    getAttributeType(propertyType).getAttribute(declaringClassName, propertyType)

                typeSpec.addProperty(
                    PropertySpec.builder(property.simpleName.asString(), abstractAttributeType)
                        .getter(
                            FunSpec.getterBuilder().addCode(
                                "return %L.%L as %L",
                                generatedClassName.canonicalName,
                                property.simpleName.asString(),
                                abstractAttributeType
                            ).build()
                        )
                        .addKdoc("@see %L.%L", declaringClassName.canonicalName, property.simpleName.asString())
                        .addAnnotations(deprecatedAnnotations)
                        .build()
                )
                declaringClass.members.addFirst(typeSpec.build())
                classMap[declaringClassName] = declaringClass.build()
            }
        }
        properties.sortWith(
            compareBy(
            // Hack to make the strings come first
            { if (it.type == String::class.asTypeName()) "0" else it.type.toString() }, { it.name })
        )
        type.addProperties(properties)
        val classType = if (clazz.isAbstract()) MappedSuperclassType::class else EntityType::class
        type.addProperty(
            PropertySpec.builder(
                "class_",
                classType.asClassName().parameterizedBy(clazz.asStarProjectedType().toTypeName()),
                KModifier.LATEINIT
            ).addKdoc("@see %L", originalClassName.canonicalName).addAnnotation(Volatile::class).mutable().build()
        )
        classMap[originalClassName] =
            file.addAnnotation(AnnotationSpec.builder(Suppress::class).addMember("%S", "warnings").build())
            .addType(type.build())
            .addProperties(functions)
            .addFunctions(joinFunctions)
            .build()
    }

    private fun getAttributeType(propertyType: KSType): AttributeType {
        val propertyType = resolveTypeAlias(propertyType)
        return if (map.asStarProjectedType().isAssignableFrom(propertyType)) {
            AttributeType.MAP
        } else if (list.asStarProjectedType().isAssignableFrom(propertyType)) {
            AttributeType.LIST
        } else if (set.asStarProjectedType().isAssignableFrom(propertyType)) {
            AttributeType.SET
        } else if (collection.asStarProjectedType().isAssignableFrom(propertyType)) {
            AttributeType.COLLECTION
        } else {
            AttributeType.SINGULAR
        }
    }

    private fun resolveTypeAlias(propertyType: KSType): KSType {
        if (propertyType.declaration is KSTypeAlias) return resolveTypeAlias((propertyType.declaration as KSTypeAlias).type.resolve())
        return propertyType
    }

    private fun camelToShoutCase(str: String): String = str.replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()
}

enum class AttributeType(
    private val klass: KClass<*>,
    private val attributeKlass: KClass<out Attribute<*, *>>,
    private val joinKlass: KClass<out Join<*, *>>
) {
    SINGULAR(Any::class, SingularAttribute::class, Join::class), MAP(
        Map::class, MapAttribute::class, MapJoin::class
    ),
    COLLECTION(Collection::class, CollectionAttribute::class, CollectionJoin::class), LIST(
        List::class, ListAttribute::class, ListJoin::class
    ),
    SET(Set::class, SetAttribute::class, SetJoin::class);

    fun getType(propertyType: KSType): ParameterizedTypeName {
        val parameters = getTypeParameters(propertyType)
        if (this == SINGULAR) return Path::class.asClassName().parameterizedBy(parameters[0])
        return Expression::class.asClassName().parameterizedBy(klass.asClassName().parameterizedBy(parameters))
    }

    fun getAttribute(declaringClass: TypeName, propertyType: KSType): ParameterizedTypeName {
        val args = mutableListOf(declaringClass)
        args.addAll(getTypeParameters(propertyType))
        return attributeKlass.asClassName().parameterizedBy(args)
    }

    fun getJoin(declaringClass: TypeName, propertyType: KSType): ParameterizedTypeName {
        val args = mutableListOf(declaringClass)
        args.addAll(getTypeParameters(propertyType))
        return joinKlass.asClassName().parameterizedBy(args)
    }

    private fun getTypeParameters(propertyType: KSType): List<TypeName> {
        return when (this) {
            SINGULAR -> listOf(propertyType.toTypeName().copy(false))
            COLLECTION, LIST, SET -> listOf(propertyType.arguments[0].toTypeName().copy(false))
            MAP -> listOf(
                propertyType.arguments[0].toTypeName().copy(false), propertyType.arguments[1].toTypeName().copy(false)
            )
        }
    }
}

val joinableAnnotations = arrayOf(OneToOne::class, OneToMany::class, ManyToOne::class, ManyToMany::class)
fun KSAnnotated.getAnnotation(klass: KClass<out Annotation>): KSAnnotation? {
    return this.annotations.find {
        it.shortName.getShortName() == klass.simpleName && it.annotationType.resolve().declaration.qualifiedName?.asString() == klass.qualifiedName
    }
}

fun KSAnnotated.isJoinable(): Boolean {
    return joinableAnnotations.any { getAnnotation(it) != null }
}
