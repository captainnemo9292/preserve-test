"""Every prompt and feedback message the agent sends.

The wording follows AutoCodeSherpa's PBT prompts where it carries over; the Java/JQF
specifics and the fixed test structure (which step 2 derives the preservation test from)
are ours. Placeholders are filled with str.format, so literal braces are doubled.
"""

SYSTEM = """You are an expert software engineer analyzing bug reports.
Your task is to understand bugs and extract concrete examples that demonstrate them."""

GENERALIZE = """Analyze this bug report and extract:
1. A 1-2 sentence summary of the bug (issue_gist)
2. Concrete examples showing: input, expected behavior, actual (buggy) behavior

Bug report:
{issue}"""

# --- writing the test ---------------------------------------------------------------------

TEST_STRUCTURE = """```java
package {package};

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import com.pholser.junit.quickcheck.*;
import com.pholser.junit.quickcheck.generator.*;
import edu.berkeley.cs.jqf.fuzz.*;
import org.junit.runner.RunWith;
import anonymous.log.Log;
// ... imports of the classes under test

@RunWith(JQF.class)
public class {class_simple} {{

    @Fuzz
    public void {method}(@InRange(minInt = -100, maxInt = 100) int x,
                         @InRange(minDouble = 0, maxDouble = 1) double y) {{  // The ranges cover the whole valid input domain, not only the bug-triggering inputs
        boolean validIf = <inputs that are legal for the API, within the parameter ranges>;
        boolean buggyIf = <inputs that trigger the bug>;

        assumeTrue(validIf);
        assumeTrue(buggyIf);

        <call the code under test; keep what you will check in local variables>

        Log.logOutIf(true, () -> new Object[]{{ <what the code returned and the resulting state> }});
        <local variables only the assertions need, e.g. the expected value>
        <assertions that fail on the buggy code and would pass once it is fixed>
    }}
}}
```"""

SYMBOLIZE = """Write a JQF property-based test to reproduce this bug.

Project root: {root}
Test file: {test_path}
Test class: {test_class}
Test method: {test_method}

Bug summary: {issue_gist}

Concrete examples:
{concrete_examples}

Use exactly this structure for the property-based test:
{structure}

You may explore the codebase at {root} with your Read, Grep and Glob tools to understand the function signatures, types, code structure, and required imports.

IMPORTANT - the test defines two input domains, and both must be as exact as you can make them:
1. VALID DOMAIN = the parameter ranges together with validIf. They must cover ALL valid inputs of the code under test, as completely as possible - not only the inputs that trigger the bug.
2. BUG DOMAIN = buggyIf. It must be true for ALL inputs that trigger the bug, and ONLY for inputs that trigger the bug. Do not leave out bug-triggering inputs (e.g. only one side of an invalid range), and do not include inputs for which the buggy code already behaves correctly.
Every valid input outside buggyIf will later be treated as an input whose behavior must stay unchanged by a fix, so a buggyIf that is too narrow or too wide makes the test wrong.

PARAMETERS = API ARGUMENTS:
Give the test one parameter per argument of the API you call, including the constructor arguments and the calls that set up the object's state (their number, arguments and order). Primitive arguments use @InRange; strings, arrays, collections and objects use a @From generator that produces the whole value, so the test explores the API's input space rather than your concrete example. Keep a value fixed only when the API requires that exact value.

A generator is a static nested class in the test file:
    public static class WordsGen extends Generator<String[]> {{
        public WordsGen() {{ super(String[].class); }}
        @Override public String[] generate(SourceOfRandomness r, GenerationStatus status) {{ ... }}
    }}
used as `@From(WordsGen.class) String[] words`. Use only `r` for randomness (no Math.random, Random or time). Cover the valid domain (including null, empty and boundary values where the API allows them), but produce bug-triggering inputs often (say 30-50% of the time). Needed imports: com.pholser.junit.quickcheck.From, com.pholser.junit.quickcheck.generator.Generator, com.pholser.junit.quickcheck.generator.GenerationStatus, com.pholser.junit.quickcheck.random.SourceOfRandomness.

LOG THE OBSERVABLE BEHAVIOR:
The Log.logOutIf line should record what the code did, not only what your assertions check: every return value, the class of any exception thrown, and the resulting state of the objects you used (e.g. all elements in order, sizes, values read through getters). The preservation test compares exactly these values between versions, so anything not logged cannot reveal a difference. Log only deterministic values (no identity hash codes, times, or hash-ordered collections).

Guidelines:
- validIf and buggyIf are boolean expressions over the parameters only; they must not call the code under test
- All generated inputs that satisfy validIf and buggyIf should trigger the bug
- The test should represent the general case of the issue, not be specific to any single input
- Generalize beyond specific inputs, but do not vary external resources (URLs, files)
- Give every parameter @InRange(...) (e.g. minInt/maxInt, minLong/maxLong, minDouble/maxDouble) or @From(SomeGenerator.class) with a static nested generator class; an unannotated double only ranges over [0, 1)
- The upper bound of an int range is excluded: @InRange(minInt = a, maxInt = b) produces only a to b - 1, so a parameter that should take the values 0 to n is @InRange(minInt = 0, maxInt = n + 1). The same holds for short, byte and char ranges; a long range (minLong/maxLong) includes both bounds. For a yes/no choice use a boolean parameter, which takes both values
- The parameter ranges together with validIf must describe the valid input domain of the code under test as completely as possible: every kind of legal input should be generated, including the inputs that do NOT trigger the bug. The ranges give the outer bounds, and validIf removes the illegal inputs inside them
- Do not narrow the parameter ranges or validIf to the bug-triggering inputs; buggyIf alone selects those. The same ranges and validIf are reused later to test the inputs outside buggyIf
- Bound the ranges reasonably (unless the bug report specifies otherwise), but wide enough that both bug-triggering and non-triggering legal inputs are generated often
- Right before the assertions, log the observable behavior (see LOG THE OBSERVABLE BEHAVIOR) with one Log.logOutIf(true, () -> new T[]{{ ... }}) line; T is Double, Integer, Long, Boolean or String (Object only for mixed types); log objects via toString() or their relevant getters
- Only local variable declarations and assertions may follow the Log.logOutIf(true, ...) line, and at least one assertion must. Compute the values that only the assertions need (e.g. the expected value) there, after the log line: the preservation test is derived from everything before the log line, with the code after it left out, so a computation placed before it runs on every input of the preservation test too and can throw there. Variables used in the log's lambda must be effectively final
- If the code under test throws, catch the exception and record what happened in a variable (e.g. its class name), then log and assert on it; the test must fail through an assertion
- Avoid using mocks; reveal genuine bug behavior
- Do not modify source code; only create tests
- Do not install new libraries; use the existing environment
- If the test does not reproduce the bug, revise the test rather than changing the environment
- Write a standalone test: no JUnit fixtures (@Before, @Rule) or project test base classes
- Import and call the relevant class directly rather than relying on the project's test infrastructure

Requirements:
- Use the class name, method name and structure given above
- Use Java 8 and JUnit 4 assertions (for doubles: assertEquals(expected, actual, delta))
- The test should fail on buggy code (trigger the bug)
- Include necessary imports (JQF, the classes being tested, etc.)
- Do not run the test yourself; it will be compiled and run on the buggy code for you

Output:
- pbt: The complete Java source of the test file
"""

FILTER_PASSING = """Project root: {root}

The test passes for some inputs, indicating that buggyIf accepts inputs that do not reproduce the issue. Examples of such inputs and the values the test logged:

    {passing}

First, identify what distinguishes these inputs from the failing ones. Then, refine the buggyIf expression to exclude these inputs (and validIf, if they are not legal for the API). Do not hide them by narrowing the @InRange bounds or with an early return, as this will classify inputs as passing or leave buggyIf wrong: the parameter ranges and validIf must keep covering the whole valid input domain. Keep the required structure.

You may explore the codebase at {root} with your Read, Grep and Glob tools before making changes.

Output:
- pbt: The refined test source code
"""

# --- checking the test --------------------------------------------------------------------

# Without the fixed version in the loop, this check is the only place a too-wide buggyIf can
# be caught, so the test is shown and questions (4)-(6) are asked about its two domains.
RELEVANCE = """Determine whether the following test failures reproduce the reported bug.

=== Bug Report ===
{issue}

=== Test ===
```java
{test}
```

=== Failing Inputs and Stacktraces ===
{failing}

Analyze whether these failures demonstrate the same bug described in the report. Consider: \
(1) Do the inputs match the conditions described? \
(2) Does the error match the expected behavior? \
(3) Is this the same root cause or a different issue? \
(4) Does buggyIf accept inputs for which the buggy code already behaves correctly, so the test \
fails there only because its assertion is wrong for them? \
(5) Does buggyIf miss inputs that also trigger the reported bug (it must cover all \
bug-triggering inputs)? \
(6) Do the parameter ranges and validIf cover the valid input domain, or are they restricted \
to the bug-triggering inputs? \
Set is_relevant=False if any of (4), (5) or (6) is a problem."""

MOCKING_CHECK = """Check whether the following test uses mocking or simulation to fake code \
behavior (e.g., Mockito, EasyMock, PowerMock, fake subclasses overriding the code under test, \
or manually simulating return values instead of calling actual code).

```java
{test}
```

Set is_valid=True if the test directly exercises the actual code without mocking or \
simulation. Set is_valid=False if mocking or simulation is used."""

HARDCODE_CHECK = """Check whether the following test contains hardcoded failures, like \
`fail()`, `assertTrue(false)`, or `assertEquals(1, 0)`. Such a test is impossible to pass and \
makes no sense.

```java
{test}
```

Set is_valid=True if the test does NOT contain hardcoded failures (i.e., it's possible to \
pass). Set is_valid=False if hardcoded failures exist."""

EQUALITY_CHECK = """Analyze the assertion in the test. Is the equality check performed \
correctly? Note that not all comparisons should use `assertEquals` or `==`. For example, \
doubles need a delta (`assertEquals(expected, actual, delta)`), objects and strings must not \
be compared with `==`, and arrays need `assertArrayEquals`.

```java
{test}
```

Set is_valid=True if the equality check is performed correctly, or if no equality check is \
present. Set is_valid=False if the equality check is improper."""

# --- feedback after a run -----------------------------------------------------------------

BAD_STRUCTURE = """The test does not follow the required structure:
{problems}

Revise the test so that it follows the structure exactly."""

COMPILE_ERROR = """The test does not compile:

{errors}

Fix the compilation errors and regenerate the test. It is compiled with Java 8 against the \
project's classes, JUnit 4 and JQF."""

# no input was checked: why
NO_INPUT_GENERATOR_FAILED = (
    "Fuzzing stopped before any input was checked: {error}. This happens when generating an "
    "input fails, e.g. a @From generator throws an exception or reads no randomness. Fix the "
    "generators.")
NO_INPUT_NONE_MATCHED = (
    "None of the {trials} generated inputs satisfied validIf and buggyIf, so the property was "
    "never checked. Keep the parameter ranges covering the whole valid input domain, but choose "
    "them (or write a @From generator) so that inputs satisfying validIf and buggyIf are "
    "generated often.")

TEST_PASSED = """The reproducer test passed, indicating it does not reproduce the bug.

=== Execution Result ===
{result}
========================

Revise the test to ensure it fails when the bug is triggered. The test must fail through an \
assertion on the logged values.{examples}"""

EXAMPLES_REMINDER = """

For reference, here are the concrete examples extracted from the bug report (consider whether \
your test covers similar scenarios):

{examples}"""

WRONG_EXCEPTION = """{failing}

The test raises unexpected exception types. Expected: AssertionError. Actual: {raised}.

The bug must show as an assertion failure on the logged values. Catch exceptions thrown by \
the code under test, record what happened in a variable, log it and assert on it; if the \
exception comes from the test itself, fix the test."""

NOT_RELEVANT = """{failing}

Analysis: {reasoning}

The failing inputs do not reproduce the reported bug. Revise the test (buggyIf, the code it \
calls, or its assertions) to match the issue description."""

USES_MOCKING = (
    "The test contains mocking or simulation constructs. Tests must exercise actual code "
    "behavior to ensure genuine bug reproduction. Please revise the test to directly invoke the "
    "relevant code paths without mocking or simulating behavior.")

PRESERVE_REFINE = """The following JQF test's REPRO method ({{testBugReproduction}}) is valid.
The PRESERVE method ({preserve_method}) has been mechanically derived from it
(preserveIf = !buggyIf). The goal now is to improve the PRESERVE method's
`Log.logOutIf(preserveIf, ...)` so that patched vs buggy outputs are more
distinguishable — without changing predicates or structure.

The fuzzer ran the preserve method on the BUGGY version only and reports the
following statistics on log diversity:

{feedback}

Four rules:
  R1. ONLY modify the preserve method `{preserve_method}`'s logOutIf call arguments.
      Change which values are logged, not the surrounding logic.
  R2. Do NOT change `buggyIf`, `validIf`, `preserveIf` predicates.
  R3. Do NOT change the repro method `testBugReproduction`.
  R4. Keep every @Fuzz annotation, parameter list, and method signature.

Ideas for richer logging (choose only values that come from the already-computed
state of the test — do not introduce new computation that could fail):
  - include a scalar hash of the return value
  - include collection sizes, array lengths, string lengths
  - include type names of returned objects
  - include numeric bit representation for doubles
  - include specific fields that the API exposes (via getters already in your test)

Current full test file:
```java
{test}
```

Return the full updated test source (with the repro method byte-identical,
only the preserve method's logOutIf arguments changed).
"""
