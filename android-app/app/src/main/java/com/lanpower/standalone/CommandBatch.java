package com.lanpower.standalone;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** Bounded fan-out: a slow endpoint never holds up dispatch to other machines. */
final class CommandBatch {
    interface Progress<T> { void completed(T target, String result, int count); }
    private static final class Result<T> {
        final T target; final String value;
        Result(T target, String value) { this.target = target; this.value = value; }
    }
    static <T> List<String> run(List<T> targets, int parallelism, Function<T, String> action, Progress<T> progress) throws Exception {
        if (targets.isEmpty()) return Collections.emptyList();
        ExecutorService workers = Executors.newFixedThreadPool(Math.min(Math.max(1, parallelism), targets.size()));
        CompletionService<Result<T>> tasks = new ExecutorCompletionService<>(workers);
        try {
            for (T target : targets) tasks.submit(() -> new Result<>(target, action.apply(target)));
            List<String> results = new ArrayList<>();
            for (int i = 0; i < targets.size(); i++) {
                Result<T> result = tasks.take().get();
                results.add(result.value);
                progress.completed(result.target, result.value, results.size());
            }
            return results;
        } finally { workers.shutdownNow(); }
    }
}
