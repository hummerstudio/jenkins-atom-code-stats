# jenkins-atom-code-stats

Code statistics — port of **sloccount / cloc（同类需求）** (—) as an atom.

No cloc needed: the counting rules (which directories to skip, whether generated code counts) are the ones
the caller states anyway.

## Build and test

```bash
mvn verify    # compiles, packages into dist/code-stats/, then runs src/test/java/atom/CodeStatsIT.java
```
