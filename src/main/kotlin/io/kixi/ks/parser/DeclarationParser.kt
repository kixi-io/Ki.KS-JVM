package io.kixi.ks.parser

import io.kixi.ks.lexer.TokenType
import io.kixi.ks.lexer.TokenType.*

/**
 * Declaration parser for the KS language.
 *
 * Handles all top-level and member declarations:
 *   - `var` / `let`   — mutable and immutable variable bindings
 *   - `fun`           — function declarations (block body, single-expr, abstract)
 *   - `class`         — class declarations with optional primary constructor
 *   - `trait`         — trait declarations with optional super-traits
 *   - `struct`        — struct declarations (value types)
 *   - `enum`          — enum declarations (simple, valued, constructor-style)
 *   - `use`           — import declarations (single, multi, wildcard, aliased)
 *   - `extend`        — type extension declarations
 *   - `static`        — static blocks inside class/enum
 *
 * @param p Reference to the parent [Parser] for token stream access.
 */
class DeclarationParser(internal val p: Parser) {



    // ====================================================================
    // Var / Let Declaration
    // ====================================================================

    /**
     * Parse a variable declaration.
     *
     *     var name = "Akiko"           mutable, inferred type
     *     let age = 42                 immutable, inferred type
     *     var height: Double = 68.0    explicit type
     *     let score: Int > 0 = 100     with constraint
     *     var item: String?            nullable, no initializer
     *     var x: Int                   typed, no initializer
     */
    fun parseVarDecl(): VarDecl {
        val loc = p.currentLocation()
        val mutable = p.peek().type == VAR
        p.advance() // consume var/let

        val name = p.expectIdentifier("Expected variable name")

        var typeAnnotation: TypeRef? = null
        var constraint: Constraint? = null
        var initializer: Expr? = null

        // Optional type annotation: : Type
        if (p.match(COLON)) {
            typeAnnotation = p.types.parseTypeRef()
            // Optional constraint after type
            constraint = p.types.tryParseConstraint()
        }

        // Optional initializer: = expr
        if (p.match(EQUAL)) {
            initializer = p.expr.parseExpression()
        }

        return VarDecl(name, mutable, typeAnnotation, constraint, initializer, loc)
    }

    // ====================================================================
    // Function Declaration
    // ====================================================================

    /**
     * Parse an infix function declaration.
     *
     *     infix fun add(other: Int): Int = this.value + other
     *
     * Consumes `infix`, then delegates to [parseFunDecl] with isInfix = true.
     * Validates that the function has exactly one parameter (enforced by parseFunDecl).
     */
    fun parseInfixFunDecl(): FunDecl {
        p.advance() // consume INFIX
        if (!p.check(FUN)) {
            p.error("Expected 'fun' after 'infix'")
        }
        return parseFunDecl(isInfix = true)
    }

    /**
     * Parse a function declaration.
     *
     *     fun add(a: Int, b: Int): Int { return a + b }   block body
     *     fun greet(name: String) = "Hello $name"          single-expression
     *     fun dangerous(level) { ... }                     untyped param
     *     fun name(): String                               abstract (no body)
     *     fun factorial(n: Int): Int = if n <= 1 1 else n * factorial(n - 1)
     *     infix fun add(other: Int): Int = ...             infix (exactly 1 param)
     *
     * A function with `= expr` is marked as [FunDecl.isSingleExpr] = true.
     * A function with no body at all (no `{` and no `=`) is abstract \u2014 valid
     * only inside a trait.
     *
     * @param isInfix Whether this function was preceded by the `infix` modifier.
     *     If true, the function must have exactly one parameter.
     */
    fun parseFunDecl(isInfix: Boolean = false): FunDecl {
        val loc = p.advance().location // consume FUN

        val name = p.expectIdentifier("Expected function name")

        // Parameter list
        p.expect(LPAREN, "Expected '(' after function name")
        val params = p.types.parseParameterList()
        p.expect(RPAREN, "Expected ')' after parameters")

        // Validate infix: exactly one parameter
        if (isInfix && params.size != 1) {
            throw ParseException(
                "Infix function '$name' must have exactly one parameter (found ${params.size})",
                loc
            )
        }

        // Optional return type: : Type
        val returnType = if (p.match(COLON)) p.types.parseTypeRef() else null

        // Body: block, single-expression, or abstract (none)
        p.skipNewlines()
        val body: Node?
        val isSingleExpr: Boolean

        when {
            // Block body: { ... }
            p.check(LBRACE) -> {
                body = p.parseBlock()
                isSingleExpr = false
            }
            // Single-expression body: = expr (or = say ...)
            p.match(EQUAL) -> {
                // Handle `say` specially since it's a statement, not an expression
                body = if (p.check(SAY)) {
                    p.stmt.parseSayStmt()
                } else {
                    p.expr.parseExpression()
                }
                isSingleExpr = true
            }
            // Abstract (no body)
            else -> {
                body = null
                isSingleExpr = false
            }
        }

        return FunDecl(name, params, returnType, body, isSingleExpr, loc, isInfix)
    }

    // ====================================================================
    // Class Declaration
    // ====================================================================

    /**
     * Parse a class declaration.
     *
     *     class Dog: Animal { ... }
     *     class Person(let name: String, var age: Int = 0) { ... }
     *     class Point(x: Double, y: Double)
     *     class Foo: Bar, Baz { ... }
     *
     * Primary constructor: optional `(params)` after the class name.
     * Super types: optional `: Type1, Type2` after constructor.
     * Body: optional `{ members }`.
     */
    fun parseClassDecl(): ClassDecl {
        val loc = p.advance().location // consume CLASS
        val name = p.expectIdentifier("Expected class name")

        // Optional primary constructor parameters
        val constructorParams = if (p.match(LPAREN)) {
            val params = p.types.parseConstructorParamList()
            p.expect(RPAREN, "Expected ')' after constructor parameters")
            params
        } else {
            emptyList()
        }

        // Optional super types: : Type1, Type2
        val superTypes = if (p.match(COLON)) parseSuperTypeList() else emptyList()

        // Optional body: { members }
        p.skipNewlines()
        val members = if (p.match(LBRACE)) {
            val body = p.parseBlockBody()
            p.expect(RBRACE, "Expected '}' to close class body")
            body
        } else {
            emptyList()
        }

        return ClassDecl(name, constructorParams, superTypes, members, loc)
    }

    // ====================================================================
    // Trait Declaration
    // ====================================================================

    /**
     * Parse a trait declaration.
     *
     *     trait Animal {
     *         fun name(): String                        // abstract
     *         fun speak() = say("${name()} speaks")     // default impl
     *     }
     *     trait Sortable: Comparable { ... }
     */
    fun parseTraitDecl(): TraitDecl {
        val loc = p.advance().location // consume TRAIT
        val name = p.expectIdentifier("Expected trait name")

        // Optional super traits: : Trait1, Trait2
        val superTraits = if (p.match(COLON)) parseSuperTypeList() else emptyList()

        // Body: { members }
        p.skipNewlines()
        val members = if (p.match(LBRACE)) {
            val body = p.parseBlockBody()
            p.expect(RBRACE, "Expected '}' to close trait body")
            body
        } else {
            emptyList()
        }

        return TraitDecl(name, superTraits, members, loc)
    }

    /**
     * Parse a struct declaration.
     *
     *     struct Point(let x: Double, let y: Double)
     *     struct Color(let r: Int, let g: Int, let b: Int): Printable { ... }
     *
     * Structs require a primary constructor. All constructor params should
     * have let/var binding (enforced as warning, not hard error, for now).
     * Supertypes are restricted to traits (enforced at interpreter level).
     */
    fun parseStructDecl(): StructDecl {
        val loc = p.expect(STRUCT, "Expected 'struct'").location

        val name = p.expectIdentifier("Expected struct name")

        // Primary constructor (required for structs)
        val params = if (p.match(LPAREN)) {
            val result = p.types.parseConstructorParamList()
            p.expect(RPAREN, "Expected ')' after struct constructor parameters")
            result
        } else {
            p.error("Structs require a primary constructor: struct $name(...)")
        }

        // Trait list (no superclass allowed — enforced at interpreter level)
        val traits = mutableListOf<TypeRef>()
        if (p.match(COLON)) {
            p.skipNewlines()
            traits.add(p.types.parseTypeRef())
            while (p.match(COMMA)) {
                p.skipNewlines()
                traits.add(p.types.parseTypeRef())
            }
        }

        // Body (optional)
        p.skipNewlines()
        val members = if (p.match(LBRACE)) {
            val body = p.parseBlockBody()
            p.expect(RBRACE, "Expected '}' to close struct body")
            body
        } else {
            emptyList()
        }

        return StructDecl(name, params, traits, members, loc)
    }

    // ====================================================================
    // Enum Declaration
    // ====================================================================

    /**
     * Parse an enum declaration. KS enums support four forms:
     *
     *     enum Color { RED GREEN BLUE }                                simple
     *     enum Veggie { Olive=5 Broccoli=10 }                         with values
     *     enum Fruit: Int { Apple=1 Orange=2 }                        typed values
     *     enum HttpStatus(code: Int, msg: String) { OK(200, "OK") }   constructor
     *
     * Enums may also contain methods, properties, and static blocks as members
     * after the constants. Constants and members are separated by the first
     * occurrence of a declaration keyword (fun, var, let, static) or by
     * natural parsing: anything that isn't an identifier followed by optional
     * `=value` or `(args)` is treated as a member.
     */
    fun parseEnumDecl(): EnumDecl {
        val loc = p.advance().location // consume ENUM
        val name = p.expectIdentifier("Expected enum name")

        // Optional value type: : Int
        val valueType = if (p.check(COLON)) {
            p.advance() // consume :
            p.types.parseTypeRef()
        } else {
            null
        }

        // Optional constructor parameters: (code: Int, msg: String)
        val constructorParams = if (p.check(LPAREN)) {
            p.advance() // consume (
            val params = p.types.parseConstructorParamList()
            p.expect(RPAREN, "Expected ')' after enum constructor parameters")
            params
        } else {
            emptyList()
        }

        // Body: { constants... members... }
        p.skipNewlines()
        val constants = mutableListOf<EnumConstant>()
        val members = mutableListOf<Node>()

        if (p.match(LBRACE)) {
            p.skipSeparators()

            // Parse constants (identifiers at the start of the body)
            while (!p.check(RBRACE) && !p.isAtEnd() && isEnumConstantStart()) {
                constants.add(parseEnumConstant())
                p.match(COMMA)  // consume optional comma between constants
                p.skipSeparators()
            }

            // Parse remaining members (methods, properties, static blocks)
            while (!p.check(RBRACE) && !p.isAtEnd()) {
                members.add(p.parseItem())
                p.skipSeparators()
            }

            p.expect(RBRACE, "Expected '}' to close enum body")
        }

        return EnumDecl(name, valueType, constructorParams, constants, members, loc)
    }

    /**
     * Check if the current position is the start of an enum constant.
     *
     * An enum constant is an IDENTIFIER optionally followed by `= value` or
     * `(args)`. We distinguish constants from members by checking that the
     * current token is an IDENTIFIER and is NOT a declaration keyword.
     */
    private fun isEnumConstantStart(): Boolean {
        if (p.peek().type != IDENTIFIER) return false
        // Declaration keywords signal the start of members, not constants
        val text = p.peek().value
        return text != "fun" && text != "var" && text != "let" && text != "static"
    }

    /**
     * Parse a single enum constant.
     *
     *     Apple                    simple
     *     Olive = 5               with assigned value
     *     OK(200, "OK")           with constructor args
     */
    private fun parseEnumConstant(): EnumConstant {
        val loc = p.currentLocation()
        val name = p.advance().value // consume identifier

        return when {
            // Constructor-style: NAME(args)
            p.check(LPAREN) -> {
                p.advance() // consume (
                val args = p.expr.parseArgumentList()
                p.expect(RPAREN, "Expected ')' after enum constant arguments")
                EnumConstant(name, args, null, loc)
            }
            // Value-style: NAME = expr
            p.match(EQUAL) -> {
                val value = p.expr.parseExpression()
                EnumConstant(name, emptyList(), value, loc)
            }
            // Simple: just NAME
            else -> EnumConstant(name, emptyList(), null, loc)
        }
    }

    // ====================================================================
    // Use (Import) Declaration
    // ====================================================================

    /**
     * Consume a path segment in a `use` declaration.
     *
     * Accepts identifiers AND keywords, because JVM package names can
     * collide with KS keywords (e.g. `java.lang`, `kotlin.reflect.full`).
     * Only called for dotted path segments — the first segment after `use`
     * still uses [Parser.expectIdentifier] which is fine since no package
     * starts with a keyword.
     */
    private fun expectPathSegment(errorMsg: String): String {
        val token = p.peek()
        if (token.type == IDENTIFIER || token.type in KEYWORD_TYPES) {
            p.advance()
            return token.value
        }
        p.errorAt(token, "$errorMsg (got ${token.type}: '${token.value}')")
    }

    companion object {
        /** All keyword TokenTypes — used to allow keywords as path segments in `use`. */
        private val KEYWORD_TYPES: Set<TokenType> = TokenType.KEYWORDS.values.toSet()
    }

    /**
     * Parse a use (import) declaration.
     *
     * Supports all import forms:
     *
     *     use io.kixi.kd.Tag                     single type
     *     use io.kixi.kd.Tag, Annotation, Snip   multi-import from same package
     *     use io.kixi.kd.*                        flat wildcard
     *     use io.kixi.**                          tree wildcard
     *     use SomeClass.staticFun                 static member import
     *     use io.kixi.kd.Tag as T                aliased import
     *     use io.kixi.kd.Tag as T, Annotation as Ann   multi with aliases
     *
     * ## Parsing Strategy
     *
     * 1. Collect dot-separated identifiers into a path
     * 2. Check for wildcard endings:
     *    - `DOT STAR` → flat wildcard, path = package
     *    - `DOT STAR_STAR` → tree wildcard, path = package
     * 3. For non-wildcards, pop the last path segment as the first import name
     * 4. Check for `AS alias` on the first import
     * 5. Check for `COMMA` to parse additional imports (each with optional alias)
     */
    fun parseUseDecl(): UseDecl {
        val loc = p.advance().location // consume USE

        // Collect the full dot-separated path
        val path = mutableListOf<String>()
        path.add(p.expectIdentifier("Expected module path after 'use'"))

        var wildcard = UseWildcard.NONE

        while (p.match(DOT)) {
            when {
                // Tree wildcard: .**  (must check STAR_STAR before STAR)
                p.check(STAR_STAR) -> {
                    p.advance() // consume **
                    wildcard = UseWildcard.TREE
                    break
                }
                // Flat wildcard: .*
                p.check(STAR) -> {
                    p.advance() // consume *
                    wildcard = UseWildcard.FLAT
                    break
                }
                // Normal path segment — accept identifiers AND keywords
                // because JVM package names can collide with KS keywords
                // (e.g. java.lang, kotlin.reflect.full)
                else -> {
                    path.add(expectPathSegment("Expected identifier in module path"))
                }
            }
        }

        // Wildcard imports — path is the package, no specific imports
        if (wildcard != UseWildcard.NONE) {
            return UseDecl(path, wildcard, emptyList(), loc)
        }

        // Non-wildcard: last path segment is the first import name
        if (path.isEmpty()) {
            p.error("Expected import path")
        }

        val firstName = path.removeAt(path.lastIndex)

        // Check for alias on first import: `as Name`
        val firstAlias = if (p.match(AS)) {
            p.expectIdentifier("Expected alias name after 'as'")
        } else {
            null
        }

        val imports = mutableListOf(UseImport(firstName, firstAlias))

        // Check for multi-import: `, Name, Name as Alias, ...`
        while (p.match(COMMA)) {
            // Skip newlines after comma for multi-line use statements
            p.skipNewlines()
            val name = p.expectIdentifier("Expected import name after ','")
            val alias = if (p.match(AS)) {
                p.expectIdentifier("Expected alias name after 'as'")
            } else {
                null
            }
            imports.add(UseImport(name, alias))
        }

        return UseDecl(path, UseWildcard.NONE, imports, loc)
    }

    // ====================================================================
    // Extend Declaration
    // ====================================================================

    /**
     * Parse a type extension declaration.
     *
     *     extend String { fun isPalindrome(): Bool = ... }
     *     extend trait Comparable
     *
     * If `trait` follows `extend`, it's a trait extension (adopting a trait
     * for an existing type). Otherwise, it's a type extension adding methods.
     */
    fun parseExtendDecl(): ExtendDecl {
        val loc = p.advance().location // consume EXTEND

        val isTraitExtension = p.match(TRAIT)

        val target = p.types.parseTypeRef()

        // Optional body: { members }
        p.skipNewlines()
        val members = if (p.match(LBRACE)) {
            val body = p.parseBlockBody()
            p.expect(RBRACE, "Expected '}' to close extend body")
            body
        } else {
            emptyList()
        }

        return ExtendDecl(target, isTraitExtension, members, loc)
    }

    // ====================================================================
    // Static Block
    // ====================================================================

    /**
     * Parse a static block inside a class or enum.
     *
     *     static {
     *         let PI = 3.14
     *         fun default(): Person = Person("Anonymous", 0)
     *     }
     */
    fun parseStaticBlock(): StaticBlock {
        val loc = p.advance().location // consume STATIC
        p.skipNewlines()
        p.expect(LBRACE, "Expected '{' after 'static'")
        val members = p.parseBlockBody()
        p.expect(RBRACE, "Expected '}' to close static block")
        return StaticBlock(members, loc)
    }

    // ====================================================================
    // Helpers
    // ====================================================================

    /**
     * Parse a comma-separated list of super types.
     *
     *     : Animal
     *     : Bar, Baz
     *     : Comparable, Printable
     *
     * The leading `:` must already be consumed.
     */
    private fun parseSuperTypeList(): List<TypeRef> {
        val types = mutableListOf<TypeRef>()
        types.add(p.types.parseTypeRef())
        while (p.match(COMMA)) {
            types.add(p.types.parseTypeRef())
        }
        return types
    }
}