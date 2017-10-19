package io.github.oleksiyp.mockk

import javassist.ClassPool
import javassist.CtClass
import javassist.CtConstructor
import javassist.Loader
import javassist.bytecode.AccessFlag
import javassist.bytecode.ClassFile
import javassist.util.proxy.MethodFilter
import javassist.util.proxy.MethodHandler
import javassist.util.proxy.ProxyFactory
import javassist.util.proxy.ProxyObject
import kotlinx.coroutines.experimental.runBlocking
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runner.Runner
import org.junit.runner.notification.RunNotifier
import org.slf4j.LoggerFactory
import sun.reflect.ReflectionFactory
import java.lang.System.identityHashCode
import java.lang.reflect.Method
import java.util.*
import java.util.Collections.synchronizedList
import java.util.Collections.synchronizedMap
import java.util.logging.Level
import kotlin.coroutines.experimental.Continuation

// ---------------------------- USER FACING --------------------------------

class MockKJUnitRunner(cls: Class<*>) : Runner() {

    private val pool = TranslatingClassPool(MockKClassTranslator())
    private val loader = Loader(pool)

    init {
        loader.delegateLoadingOf("org.junit.runner.")
        Thread.currentThread().contextClassLoader = loader
    }

    private val parentRunner = ParentRunnerFinderDynamicFinder(cls) { loader.loadClass(it.name) }.runner

    override fun run(notifier: RunNotifier?) {
        parentRunner.run(notifier)
    }

    override fun getDescription(): Description = parentRunner.description
}

interface MockK {
    val spiedObj: Any
}

inline fun <reified T> mockk(): T = MockKGateway.mockk(T::class.java)

inline fun <reified T> spyk(obj: T): T = MockKGateway.spyk(T::class.java, obj)

fun <T> every(mockBlock: suspend MockKScope.() -> T): MockKStubScope<T> {
    val gw = MockKGateway.LOCATOR()
    val callRecorder = gw.callRecorder
    callRecorder.startStubbing()
    runBlocking {
        val n = MockKGateway.N_CALL_ROUNDS
        val scope = MockKScope(gw)
        repeat(n) {
            callRecorder.catchArgs(it, n)
            scope.mockBlock()
        }
        callRecorder.catchArgs(n, n)
    }
    return MockKStubScope(gw)
}

enum class Ordering {
    UNORDERED, ORDERED, SEQUENCE
}

fun <T> verify(ordering: Ordering = Ordering.UNORDERED,
               inverse: Boolean = false,
               mockBlock: suspend MockKScope.() -> T) {
    val gw = MockKGateway.LOCATOR()
    val callRecorder = gw.callRecorder
    callRecorder.startVerification()
    runBlocking {
        val n = MockKGateway.N_CALL_ROUNDS
        val scope = MockKScope(gw)
        repeat(n) {
            callRecorder.catchArgs(it, n)
            scope.mockBlock()
        }
        callRecorder.catchArgs(n, n)
    }
    callRecorder.verify(ordering, inverse)
}

fun <T> verifyOrder(inverse: Boolean = false,
                    mockBlock: suspend MockKScope.() -> T) {
    verify(Ordering.ORDERED, inverse, mockBlock)
}

fun <T> verifySequence(inverse: Boolean = false,
                       mockBlock: suspend MockKScope.() -> T) {
    verify(Ordering.SEQUENCE, inverse, mockBlock)
}

class MockKScope(@JvmSynthetic @PublishedApi internal val gw: MockKGateway) {
    inline fun <reified T> match(matcher: Matcher<T>): T {
        return MockKGateway.matcherInCall(gw, matcher)
    }

    inline fun <reified T> match(noinline matcher: (T) -> Boolean): T = match(LambdaMatcher(matcher))
    inline fun <reified T> eq(value: T): T = match(EqMatcher(value))
    inline fun <reified T> any(): T = match(ConstantMatcher(true))
    inline fun <reified T> capture(lst: MutableList<T>): T = match(CaptureMatcher(lst))
}

class MockKStubScope<T>(@JvmSynthetic @PublishedApi internal val gw: MockKGateway) {
    infix fun answers(answer: Answer<T?>) = gw.callRecorder.answer(answer)

    infix fun returns(returnValue: T?) = answers(ConstantAnswer(returnValue))

    infix fun answers(answer: MockKAnswerScope.(Invocation) -> T?) =
            answers(LambdaAnswer({ MockKAnswerScope(gw, it).answer(it) }))
}

class MockKAnswerScope(private val gw: MockKGateway,
                       val invocation: Invocation) {

    val self
        get() = invocation.self as MockK

    inline fun <reified T> spiedObj() = self.spiedObj as T

    val method
        get() = invocation.method

    val args
        get() = invocation.args

    val nArgs
        get() = invocation.args.size

    inline fun <reified T> firstArg() = invocation.args[0] as T
    inline fun <reified T> secondArg() = invocation.args[1] as T
    inline fun <reified T> thirdArg() = invocation.args[2] as T
    inline fun <reified T> lastArg() = invocation.args[invocation.args.size - 1] as T

    inline fun <T> MutableList<T>.captured() = get(size - 1)
}


data class EqMatcher<T>(val value: T) : Matcher<T> {
    override fun match(arg: T): Boolean = arg == value

    override fun toString(): String = "eq(" + MockKGateway.toString(value) + ")"
}

data class ConstantMatcher<T>(val constValue: Boolean) : Matcher<T> {
    override fun match(arg: T): Boolean = constValue

    override fun toString(): String = if (constValue) "any()" else "none()"
}

data class LambdaMatcher<T>(val matchingFunc: (T) -> Boolean) : Matcher<T> {
    override fun match(arg: T): Boolean = matchingFunc(arg)

    override fun toString(): String = "matcher()"
}

data class CaptureMatcher<T>(val captureList: MutableList<T>) : Matcher<T>, CapturingMatcher<T> {
    override fun capture(arg: Any) {
        captureList.add(arg as T)
    }

    override fun match(arg: T): Boolean = true

    override fun toString(): String = "capture()"
}


data class ConstantAnswer<T>(val constantValue: T?) : Answer<T?> {
    override fun answer(invocation: Invocation) = constantValue

    override fun toString(): String = "const($constantValue)"
}

data class LambdaAnswer<T>(val answerFunc: (Invocation) -> T?) : Answer<T?> {
    override fun answer(invocation: Invocation): T? = answerFunc(invocation)

    override fun toString(): String = "answer()"
}

class MockKException(message: String) : RuntimeException(message)

// ---------------------------- INTERFACES --------------------------------
interface MockKGateway {
    val callRecorder: CallRecorder

    val instantiator: Instantiator

    companion object {
        val defaultImpl = MockKGatewayImpl()
        var LOCATOR: () -> MockKGateway = { defaultImpl }

        private val log = logger<MockKGateway>()

        private val NO_ARGS_TYPE = Class.forName("\$NoArgsConstructorParamType")

        private fun <T> proxy(cls: Class<T>): Any? {
            val factory = ProxyFactory()

            log.debug { "Building proxy for $cls" }

            return if (cls.isInterface) {
                factory.interfaces = arrayOf(cls, MockKInstance::class.java)
                factory.create(emptyArray(), emptyArray())
            } else {
                factory.interfaces = arrayOf(MockKInstance::class.java)
                factory.superclass = cls
                factory.create(arrayOf(NO_ARGS_TYPE), arrayOf<Any?>(null))
            }
        }

        fun <T> mockk(cls: Class<T>): T {
            log.info { "Creating mockk for $cls" }
            val obj = proxy(cls)
            (obj as ProxyObject).handler = MockKInstanceProxyHandler(cls, obj)
            return cls.cast(obj)
        }

        fun <T> spyk(cls: Class<T>, spiedObj: T): T {
            log.info { "Creating spyk for $cls" }
            val obj = proxy(cls)
            (obj as ProxyObject).handler = SpyKInstanceProxyHandler(cls, obj, spiedObj)
            return cls.cast(obj)
        }

        fun anyValue(type: Class<*>, block: () -> Any? = { MockKGateway.LOCATOR().instantiator.instantiate(type) }): Any? {
            return when (type) {
                Void.TYPE -> Unit
                Boolean::class.java -> false
                Byte::class.java -> 0.toByte()
                Short::class.java -> 0.toShort()
                Int::class.java -> 0
                Long::class.java -> 0L
                Float::class.java -> 0.0F
                Double::class.java -> 0.0
                String::class.java -> ""
                Object::class.java -> Object()
                else -> block()
            }
        }

        inline fun <reified T> matcherInCall(gw: MockKGateway, matcher: Matcher<T>): T {
            return gw.callRecorder.matcher(matcher, T::class.java)
        }

        fun toString(obj: Any?): String {
            if (obj == null)
                return "null"
            if (obj is Method)
                return obj.toStr()
            return obj.toString()
        }

        val N_CALL_ROUNDS: Int = 64
    }

    fun verifier(ordering: Ordering): Verifier
}

interface CallRecorder {
    fun startStubbing()

    fun startVerification()

    fun catchArgs(round: Int, n: Int)

    fun <T> matcher(matcher: Matcher<*>, cls: Class<T>): T

    fun call(invocation: Invocation): Any?

    fun answer(answer: Answer<*>)

    fun verify(ordering: Ordering, inverse: Boolean)
}

data class VerificationResult(val matches: Boolean, val matcher: InvocationMatcher? = null)

interface Verifier {
    fun verify(matchers: List<InvocationMatcherWithCall>): VerificationResult
}

interface Instantiator {
    fun <T> instantiate(cls: Class<T>): T
}

data class Invocation(val self: MockKInstance,
                      val method: Method,
                      val args: List<Any>,
                      val timestamp: Long = System.currentTimeMillis()) {
    override fun toString(): String {
        return "Invocation(self=$self, method=${MockKGateway.toString(method)}, args=$args)"
    }
}

data class InvocationMatcher(val self: Matcher<Any>,
                             val method: Matcher<Method>,
                             val args: List<Matcher<Any>>) {
    fun match(invocation: Invocation): Boolean {
        if (!self.match(invocation.self)) {
            return false
        }
        if (!method.match(invocation.method)) {
            return false
        }
        if (args.size != invocation.args.size) {
            return false
        }

        for (i in 0 until args.size) {
            if (!args[i].match(invocation.args[i])) {
                return false
            }
        }

        return true
    }
}

data class InvocationMatcherWithCall(val invocation: Invocation, val matcher: InvocationMatcher)

interface Matcher<in T> {
    fun match(arg: T): Boolean
}

interface CapturingMatcher<in T> {
    fun capture(arg: Any)
}

interface Answer<T> {
    fun answer(invocation: Invocation): T
}

// ---------------------------- IMPLEMENTATION --------------------------------

interface MockKInstance : MockK {
    fun ___type(): Class<*>

    fun ___addAnswer(matcher: InvocationMatcher, answer: Answer<*>)

    fun ___findAnswerAndCapture(invocation: Invocation): Answer<*>

    fun ___childMockK(invocation: Invocation): MockKInstance

    fun ___recordCalls(invocation: Invocation)

    fun ___matchesAnyRecordedCalls(matcher: InvocationMatcher): Boolean

    fun ___allRecordedCalls(): List<Invocation>
}

private fun Method.toStr() =
        name + "(" + parameterTypes.map { it.simpleName }.joinToString() + ")"

private open class MockKInstanceProxyHandler(private val cls: Class<*>,
                                             private val obj: Any) : MethodHandler, MockKInstance {

    private val answers = synchronizedList(mutableListOf<Pair<InvocationMatcher, Answer<*>>>())
    private val mocks = synchronizedMap(hashMapOf<Invocation, MockKInstance>())
    private val recordedCalls = synchronizedList(mutableListOf<Invocation>())

    override val spiedObj: Any
        get() = throw MockKException("spiedObj is actual only for spies")

    override fun ___addAnswer(matcher: InvocationMatcher, answer: Answer<*>) {
        answers.add(Pair(matcher, answer))
    }

    override fun ___findAnswerAndCapture(invocation: Invocation): Answer<*> {
        return synchronized(answers) {
            val invocationAndMatcher = answers.firstOrNull { it.first.match(invocation) }
            invocationAndMatcher?.let {
                ___captureAnswer(it.first, invocation)
                it.second
            } ?: ConstantAnswer(defaultAnswer(invocation))
        }
    }

    private fun ___captureAnswer(invocationMatcher: InvocationMatcher, invocation: Invocation) {
        repeat(invocationMatcher.args.size) {
            val argMatcher = invocationMatcher.args[it]
            if (argMatcher is CapturingMatcher<*>) {
                argMatcher.capture(invocation.args[it])
            }
        }
    }

    protected open fun defaultAnswer(invocation: Invocation): Any? {
        return MockKGateway.anyValue(invocation.method.returnType) {
            ___childMockK(invocation)
        }
    }

    override fun ___recordCalls(invocation: Invocation) {
        recordedCalls.add(invocation)
    }

    override fun ___matchesAnyRecordedCalls(matcher: InvocationMatcher): Boolean {
        synchronized(recordedCalls) {
            return recordedCalls.any { matcher.match(it) }
        }
    }

    override fun ___allRecordedCalls(): List<Invocation> {
        synchronized(recordedCalls) {
            return recordedCalls.toList()
        }
    }

    override fun ___type(): Class<*> = cls

    override fun toString() = "mockk<" + ___type().simpleName + ">()"

    override fun equals(other: Any?): Boolean {
        return obj === other
    }

    override fun hashCode(): Int {
        return identityHashCode(obj)
    }

    override fun ___childMockK(invocation: Invocation): MockKInstance {
        return mocks.computeIfAbsent(invocation, {
            MockKGateway.mockk(invocation.method.returnType) as MockKInstance
        })
    }

    override fun invoke(self: Any,
                        thisMethod: Method,
                        proceed: Method?,
                        args: Array<out Any>): Any? {

        findMethodInProxy(this, thisMethod)?.let {
            return it.invoke(this, *args)
        }

        val argList = args.toList()
        val invocation = Invocation(self as MockKInstance, thisMethod, argList)
        return MockKGateway.LOCATOR().callRecorder.call(invocation)
    }

    private fun findMethodInProxy(obj: Any,
                                  method: Method): Method? {
        return obj.javaClass.methods.find {
            it.name == method.name &&
                    Arrays.equals(it.parameterTypes, method.parameterTypes)
        }
    }
}


private class SpyKInstanceProxyHandler<T>(cls: Class<T>, obj: ProxyObject,
                                          private val _spiedObj: T) : MockKInstanceProxyHandler(cls, obj) {
    override val spiedObj
        get() = _spiedObj as Any

    override fun defaultAnswer(invocation: Invocation): Any? {
        return invocation.method.invoke(_spiedObj, *invocation.args.toTypedArray())
    }

    override fun toString(): String = "spyk<" + ___type().simpleName + ">()"
}


class MockKGatewayImpl : MockKGateway {
    private val callRecorderTL = ThreadLocal.withInitial { CallRecorderImpl(this) }
    private val instantiatorTL = ThreadLocal.withInitial { InstantiatorImpl(this) }
    private val unorderedVerifierTL = ThreadLocal.withInitial { UnorderedVerifierImpl(this) }
    private val orderedVerifierTL = ThreadLocal.withInitial { OrderedVerifierImpl(this) }
    private val sequenceVerifierTL = ThreadLocal.withInitial { SequenceVerifierImpl(this) }

    override val callRecorder: CallRecorder
        get() = callRecorderTL.get()

    override val instantiator: Instantiator
        get() = instantiatorTL.get()

    override fun verifier(ordering: Ordering): Verifier =
            when (ordering) {
                Ordering.UNORDERED -> unorderedVerifierTL.get()
                Ordering.ORDERED -> orderedVerifierTL.get()
                Ordering.SEQUENCE -> sequenceVerifierTL.get()
            }
}

private class Ref(val value: Any) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Ref

        if (value !== other.value) return false

        return true
    }

    override fun hashCode(): Int = identityHashCode(value)
    override fun toString(): String = "Ref(${value.javaClass.simpleName}@${hashCode()})"
}

private data class SignedCall(val invocation: Invocation,
                              val matchers: List<Matcher<*>>,
                              val signaturePart: List<Any>)

private data class CallRound(val calls: List<SignedCall>)

private class CallRecorderImpl(private val gw: MockKGateway) : CallRecorder {
    private val log = logger<CallRecorderImpl>()

    private enum class Mode {
        STUBBING, VERIFYING, ANSWERING
    }

    private var mode = Mode.ANSWERING

    private val rnd = Random()

    private val signedCalls = mutableListOf<SignedCall>()
    private val callRounds = mutableListOf<CallRound>()
    private val invocationMatchers = mutableListOf<InvocationMatcherWithCall>()
    private val childMocks = mutableListOf<MockK>()

    val matchers = mutableListOf<Matcher<*>>()
    val signatures = mutableListOf<Any>()

    fun checkMode(vararg modes: Mode) {
        if (!modes.any { it == mode }) {
            throw MockKException("Bad recording sequence. Mode: $mode")
        }
    }

    override fun startStubbing() {
        log.info { "Starting stubbing" }
        checkMode(Mode.ANSWERING)
        mode = Mode.STUBBING
    }

    override fun startVerification() {
        log.info { "Starting verification" }
        checkMode(Mode.ANSWERING)
        mode = Mode.VERIFYING
    }

    override fun catchArgs(round: Int, n: Int) {
        checkMode(Mode.STUBBING, Mode.VERIFYING)
        childMocks.clear()
        if (round > 0) {
            callRounds.add(CallRound(signedCalls.toList()))
            signedCalls.clear()
        }
        if (round == n) {
            signMatchers()
            callRounds.clear()
        }
    }

    private fun signMatchers() {
        val nCalls = callRounds[0].calls.size
        if (nCalls == 0) {
            throw MockKException("No calls inside every/verify {} block")
        }
        if (callRounds.any { it.calls.size != nCalls }) {
            throw MockKException("Not all call rounds result in same amount of calls")
        }
        callRounds.forEach { callRound ->
            val callZero = callRound.calls[0]
            if (callRound.calls.any {
                it.matchers.size != callZero.matchers.size ||
                        it.invocation.args.size != callZero.invocation.args.size ||
                        it.signaturePart.size != callZero.signaturePart.size
            }) {
                throw MockKException("Not all calls attached to same number of matchers and arguments")
            }
        }

        invocationMatchers.clear()

        repeat(nCalls) { callN ->

            val callInAllRounds = callRounds.map { it.calls[callN] }
            val matcherMap = hashMapOf<List<Any>, Matcher<*>>()
            val zeroCall = callInAllRounds[0]

            log.info { "Processing call #${callN}: ${zeroCall.invocation.method.toStr()}" }

            repeat(zeroCall.matchers.size) { nMatcher ->
                val matcher = callInAllRounds.map { it.matchers[nMatcher] }.last()
                val signature = callInAllRounds.map { it.signaturePart[nMatcher] }.toList()

                matcherMap[signature] = matcher
            }

            log.debug { "Matcher map for ${zeroCall.invocation.method.toStr()}: $matcherMap" }

            val argMatchers = mutableListOf<Matcher<*>>()

            repeat(zeroCall.invocation.args.size) { nArgument ->
                val signature = callInAllRounds.map {
                    val arg = it.invocation.args[nArgument]
                    if (byValue(it.invocation.args[nArgument].javaClass))
                        arg
                    else
                        Ref(arg)
                }.toList()

                log.debug { "Signature for $nArgument argument of ${zeroCall.invocation.method.toStr()}: $signature" }

                val matcher = matcherMap.remove(signature)
                        ?: EqMatcher(zeroCall.invocation.args[nArgument])

                argMatchers.add(matcher)
            }

            if (zeroCall.invocation.method.isSuspend()) {
                log.debug { "Suspend function found. Replacing continuation with any() matcher" }
                argMatchers[argMatchers.size - 1] = ConstantMatcher<Any>(true)
            }

            if (matcherMap.isNotEmpty()) {
                throw MockKException("Failed to find few matchers by signature: $matcherMap")
            }

            val invocationMatcher = InvocationMatcher(
                    EqMatcher(zeroCall.invocation.self),
                    EqMatcher(zeroCall.invocation.method),
                    argMatchers.toList() as List<Matcher<Any>>)
            log.info { "Built matcher: $invocationMatcher" }
            invocationMatchers.add(InvocationMatcherWithCall(zeroCall.invocation, invocationMatcher))
        }
    }

    override fun <T> matcher(matcher: Matcher<*>, cls: Class<T>): T {
        checkMode(Mode.STUBBING, Mode.VERIFYING)
        matchers.add(matcher)
        val signatureValue = signatureValue(cls)
        signatures.add(if (byValue(cls)) signatureValue as Any else Ref(signatureValue as Any))
        return signatureValue
    }


    private fun <T> signatureValue(cls: Class<T>): T {
        return cls.cast(when (cls) {
            java.lang.Boolean::class.java -> java.lang.Boolean(rnd.nextBoolean())
            java.lang.Byte::class.java -> java.lang.Byte(rnd.nextInt().toByte())
            java.lang.Short::class.java -> java.lang.Short(rnd.nextInt().toShort())
            java.lang.Character::class.java -> java.lang.Character(rnd.nextInt().toChar())
            java.lang.Integer::class.java -> java.lang.Integer(rnd.nextInt())
            java.lang.Long::class.java -> java.lang.Long(rnd.nextLong())
            java.lang.Float::class.java -> java.lang.Float(rnd.nextFloat())
            java.lang.Double::class.java -> java.lang.Double(rnd.nextDouble())
            java.lang.String::class.java -> java.lang.String(rnd.nextLong().toString(16))
            java.lang.Object::class.java -> java.lang.Object()
            else -> gw.instantiator.instantiate(cls)
        })
    }

    private fun byValue(cls: Class<*>): Boolean {
        return when (cls) {
            java.lang.Boolean::class.java -> true
            java.lang.Byte::class.java -> true
            java.lang.Short::class.java -> true
            java.lang.Character::class.java -> true
            java.lang.Integer::class.java -> true
            java.lang.Long::class.java -> true
            java.lang.Float::class.java -> true
            java.lang.Double::class.java -> true
            java.lang.String::class.java -> true
            else -> false
        }
    }

    override fun call(invocation: Invocation): Any? {
        if (mode == Mode.ANSWERING) {
            invocation.self.___recordCalls(invocation)
            val answer = invocation.self.___findAnswerAndCapture(invocation).answer(invocation)
            log.info { "Recorded call: $invocation, answer: $answer" }
            return answer
        } else {
            return addCallWithMatchers(invocation)
        }
    }

    private fun addCallWithMatchers(invocation: Invocation): Any? {
        if (matchers.size > invocation.args.size) {
            throw MockKException("More matchers then arguments")
        }
        if (childMocks.any { mock -> invocation.args.any { it === mock } }) {
            throw MockKException("Passing child mocks to arguments is prohibited")
        }

        signedCalls.add(SignedCall(invocation, matchers.toList(), signatures.toList()))
        matchers.clear()
        signatures.clear()

        return MockKGateway.anyValue(invocation.method.returnType) {
            // TODO optimize child mocks
            val child = MockKGateway.mockk(invocation.method.returnType) as MockK
            childMocks.add(child)
            child
        }
    }

    override fun answer(answer: Answer<*>) {
        checkMode(Mode.STUBBING)
        var ans = answer

        for (im in invocationMatchers.reversed()) {
            val invocation = im.invocation
            invocation.self.___addAnswer(im.matcher, ans)
            ans = ConstantAnswer(MockKGateway.anyValue(invocation.method.returnType) {
                invocation.self.___childMockK(invocation)
            })
        }

        log.debug { "Done stubbing" }
        mode = Mode.ANSWERING
    }

    override fun verify(ordering: Ordering, inverse: Boolean) {
        checkMode(Mode.VERIFYING)

        val outcome = gw.verifier(ordering).verify(invocationMatchers)

        log.debug { "Done verification. Outcome: $outcome" }
        mode = Mode.ANSWERING

        val matcherStr = if (outcome.matcher != null) ", matcher: ${outcome.matcher}" else ""

        if (inverse) {
            if (outcome.matches) {
                throw MockKException("Inverse verification failed$matcherStr")
            }
        } else {
            if (!outcome.matches) {
                throw MockKException("Verification failed$matcherStr")
            }
        }
    }
}

private class UnorderedVerifierImpl(private val gw: MockKGateway) : Verifier {
    override fun verify(matchers: List<InvocationMatcherWithCall>): VerificationResult {
        return matchers
                .firstOrNull { !it.invocation.self.___matchesAnyRecordedCalls(it.matcher) }
                ?.matcher
                ?.let { VerificationResult(false, it) }
                ?: VerificationResult(true)
    }
}

private class OrderedVerifierImpl(private val gw: MockKGateway) : Verifier {
    override fun verify(matchers: List<InvocationMatcherWithCall>): VerificationResult {
        return VerificationResult(false)
    }
}

private class SequenceVerifierImpl(private val gw: MockKGateway) : Verifier {
    override fun verify(matchers: List<InvocationMatcherWithCall>): VerificationResult {
        val allCalls = matchers.reversed()
                .map { Ref(it.invocation.self) }
                .distinct()
                .map { it.value as MockKInstance }
                .flatMap { it.___allRecordedCalls() }
                .sortedBy { it.timestamp }

        if (allCalls.size != matchers.size) {
            return VerificationResult(false)
        }

        repeat(allCalls.size) {
            if (!matchers[it].matcher.match(allCalls[it])) {
                return VerificationResult(false)
            }
        }

        return VerificationResult(true)
    }
}


private fun Method.isSuspend(): Boolean {
    if (parameterCount == 0) {
        return false
    }
    return Continuation::class.java.isAssignableFrom(parameterTypes[parameterCount - 1])
}

// ---------------------------- BYTE CODE LEVEL --------------------------------

private class InstantiatorImpl(gw: MockKGatewayImpl) : Instantiator {
    private val log = logger<InstantiatorImpl>()

    val cp = ClassPool.getDefault()

    override fun <T> instantiate(cls: Class<T>): T {
        val factory = ProxyFactory()

        val makeMethod = factory.javaClass.getDeclaredMethod("make")
        makeMethod.isAccessible = true

        val computeSignatureMethod = factory.javaClass.getDeclaredMethod("computeSignature",
                MethodFilter::class.java)
        computeSignatureMethod.isAccessible = true

        val allocateClassNameMethod = factory.javaClass.getDeclaredMethod("allocateClassName")
        allocateClassNameMethod.isAccessible = true

        val proxyClsFile = if (cls.isInterface) {
            factory.interfaces = arrayOf(cls, MockKInstance::class.java)
            computeSignatureMethod.invoke(factory, MethodFilter { true })
            allocateClassNameMethod.invoke(factory)
            makeMethod.invoke(factory)
        } else {
            factory.interfaces = arrayOf(MockKInstance::class.java)
            factory.superclass = cls
            computeSignatureMethod.invoke(factory, MethodFilter { true })
            allocateClassNameMethod.invoke(factory)
            makeMethod.invoke(factory)
        } as ClassFile

        val proxyCls = cp.makeClass(proxyClsFile).toClass()

        val instance = newEmptyInstance(proxyCls)

        (instance as ProxyObject).handler = MethodHandler { self: Any, thisMethod: Method, proceed: Method, args: Array<Any?> ->

            if (thisMethod.name == "hashCode" && thisMethod.parameterCount == 0) {
                identityHashCode(self)
            } else if (thisMethod.name == "equals" &&
                    thisMethod.parameterCount == 1 &&
                    thisMethod.parameterTypes[0] == java.lang.Object::class.java) {
                self === args[0]
            } else {
                null
            }
        }

        log.debug { "Built instance $cls" }

        return cls.cast(instance)
    }

    val reflectionFactoryFinder =
            try {
                Class.forName("sun.reflect.ReflectionFactory")
                ReflecationFactoryFinder()
            } catch (cnf: ClassNotFoundException) {
                null
            }


    private fun newEmptyInstance(proxyCls: Class<*>): Any {
        reflectionFactoryFinder?.let { return it.newEmptyInstance(proxyCls) }
        throw MockKException("no instantiation support on platform")
    }
}

private class ReflecationFactoryFinder {
    fun newEmptyInstance(proxyCls: Class<*>): Any {
        val rf = ReflectionFactory.getReflectionFactory();
        val objDef = Object::class.java.getDeclaredConstructor();
        val intConstr = rf.newConstructorForSerialization(proxyCls, objDef)
        return intConstr.newInstance()
    }
}

internal class ParentRunnerFinder(val cls: Class<*>) {
    val parentRunner = findParentRunWith()

    fun findParentRunWith(): Runner {
        var parent = cls.superclass

        while (parent != null) {
            val annotation = parent.getAnnotation(RunWith::class.java)
            if (annotation != null) {
                val constructor = annotation.value.java.getConstructor(Class::class.java)
                return constructor.newInstance(cls)
            }
            parent = parent.superclass
        }
        throw RuntimeException("not runner RunWith found")
    }
}

private class ParentRunnerFinderDynamicFinder(cls: Class<*>, instrument: (Class<*>) -> Class<*>) {
    private val finderClass = instrument(ParentRunnerFinder::class.java)
    private val finderConstructor = finderClass.getConstructor(Class::class.java)
    private val getParentRunnerMethod = finderClass.getMethod("getParentRunner")
    val runner = getParentRunnerMethod.invoke(finderConstructor.newInstance(instrument(cls))) as Runner
}

private class TranslatingClassPool(private val mockKClassTranslator: MockKClassTranslator)
    : ClassPool() {

    init {
        appendSystemPath()
        mockKClassTranslator.start(this)
    }

    override fun get0(classname: String, useCache: Boolean): CtClass {
        val cls = super.get0(classname, useCache)
        mockKClassTranslator.onLoad(cls)
        return cls
    }
}

private class MockKClassTranslator {
    lateinit var noArgsParamType: CtClass
    val log = logger<MockKClassTranslator>()

    fun start(pool: ClassPool) {
        noArgsParamType = pool.makeClass("\$NoArgsConstructorParamType")
    }

    val load = Collections.synchronizedSet(hashSetOf<String>())

    fun onLoad(cls: CtClass) {
        if (!load.add(cls.name) || cls.isFrozen) {
            return
        }
        log.debug { "Translating ${cls.name}" }
        removeFinal(cls)
        addNoArgsConstructor(cls)
        cls.freeze()
    }

    private fun addNoArgsConstructor(cls: CtClass) {
        if (cls.isAnnotation || cls.isArray || cls.isEnum || cls.isInterface) {
            return
        }

        if (cls.constructors.any { isNoArgsConstructor(it) }) {
            return
        }

        if (cls.superclass == null) {
            return
        }

        with(cls.superclass) {
            when {
                constructors.any { isNoArgsConstructor(it) } -> {
                    if (cls.constructors.any { isNoArgsConstructor(it) }) {
                        return@with
                    }

                    val newConstructor = CtConstructor(arrayOf(noArgsParamType), cls)
                    cls.addConstructor(newConstructor)
                    newConstructor.setBody("super($1);")
                }
                constructors.any { it.parameterTypes.isEmpty() } -> {
                    if (cls.constructors.any { isNoArgsConstructor(it) }) {
                        return@with
                    }

                    val newConstructor = CtConstructor(arrayOf(noArgsParamType), cls)
                    cls.addConstructor(newConstructor)
                    newConstructor.setBody("super();")
                }
            }
        }
    }

    private fun isNoArgsConstructor(it: CtConstructor) =
            it.parameterTypes.size == 1 && it.parameterTypes[0] == noArgsParamType

    fun removeFinal(clazz: CtClass) {
        removeFinalOnClass(clazz)
        removeFinalOnMethods(clazz)
        clazz.stopPruning(true)
    }

    private fun removeFinalOnMethods(clazz: CtClass) {
        clazz.declaredMethods.forEach {
            if (java.lang.reflect.Modifier.isFinal(it.modifiers)) {
                it.modifiers = javassist.Modifier.clear(it.modifiers, java.lang.reflect.Modifier.FINAL)
            }
        }
    }


    private fun removeFinalOnClass(clazz: CtClass) {
        val modifiers = clazz.modifiers
        if (java.lang.reflect.Modifier.isFinal(modifiers)) {
            clazz.classFile2.accessFlags = AccessFlag.of(javassist.Modifier.clear(modifiers, java.lang.reflect.Modifier.FINAL))
        }
    }

}


// ---------------------------- LOGGING --------------------------------

private val loggerFactory = try {
    Class.forName("org.slf4j.Logger");
    { cls: Class<*> -> Slf4jLogger(cls) }
} catch (ex: ClassNotFoundException) {
    { cls: Class<*> -> JULLogger(cls) }
}


private inline fun <reified T> logger(): Logger = loggerFactory(T::class.java)

private interface Logger {
    fun error(msg: () -> String)
    fun error(ex: Throwable, msg: () -> String)
    fun warn(msg: () -> String)
    fun warn(ex: Throwable, msg: () -> String)
    fun info(msg: () -> String)
    fun info(ex: Throwable, msg: () -> String)
    fun debug(msg: () -> String)
    fun debug(ex: Throwable, msg: () -> String)
}

private class Slf4jLogger(cls: Class<*>) : Logger {
    val log = LoggerFactory.getLogger(cls)

    override fun error(msg: () -> String) = if (log.isErrorEnabled) log.error(msg()) else Unit
    override fun error(ex: Throwable, msg: () -> String) = if (log.isErrorEnabled) log.error(msg(), ex) else Unit
    override fun warn(msg: () -> String) = if (log.isWarnEnabled) log.warn(msg()) else Unit
    override fun warn(ex: Throwable, msg: () -> String) = if (log.isWarnEnabled) log.warn(msg(), ex) else Unit
    // note library info & debug is shifted to debug & trace respectively
    override fun info(msg: () -> String) = if (log.isDebugEnabled) log.debug(msg()) else Unit

    override fun info(ex: Throwable, msg: () -> String) = if (log.isDebugEnabled) log.debug(msg(), ex) else Unit
    override fun debug(msg: () -> String) = if (log.isTraceEnabled) log.trace(msg()) else Unit
    override fun debug(ex: Throwable, msg: () -> String) = if (log.isTraceEnabled) log.trace(msg(), ex) else Unit
}

private class JULLogger(cls: Class<*>) : Logger {
    val log = java.util.logging.Logger.getLogger(cls.name)

    override fun error(msg: () -> String) = if (log.isLoggable(Level.SEVERE)) log.severe(msg()) else Unit
    override fun error(ex: Throwable, msg: () -> String) = if (log.isLoggable(Level.SEVERE)) log.log(Level.SEVERE, msg(), ex) else Unit
    override fun warn(msg: () -> String) = if (log.isLoggable(Level.WARNING)) log.warning(msg()) else Unit
    override fun warn(ex: Throwable, msg: () -> String) = if (log.isLoggable(Level.WARNING)) log.log(Level.WARNING, msg(), ex) else Unit
    // note library info & debug is shifted to debug & trace respectively
    override fun info(msg: () -> String) = if (log.isLoggable(Level.FINE)) log.fine(msg()) else Unit

    override fun info(ex: Throwable, msg: () -> String) = if (log.isLoggable(Level.FINE)) log.log(Level.FINE, msg(), ex) else Unit
    override fun debug(msg: () -> String) = if (log.isLoggable(Level.FINER)) log.finer(msg()) else Unit
    override fun debug(ex: Throwable, msg: () -> String) = if (log.isLoggable(Level.FINER)) log.log(Level.FINER, msg(), ex) else Unit
}
