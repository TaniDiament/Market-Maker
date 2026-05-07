package edu.yu.marketmaker.ha;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Defence-in-depth: if a stale client sends a write to a non-leader replica,
 * we reject it with HTTP 503 instead of processing it.
 *
 * <p>GETs are allowed on any replica because Hazelcast keeps state consistent
 * across the cluster. Only mutating methods are gated.
 *
 * <p>The health endpoint is always allowed so orchestrators can poll standbys.
 */
public class LeaderGuardFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(LeaderGuardFilter.class);

    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> ALWAYS_ALLOWED_PATHS = Set.of(
            "/health", "/actuator/health", "/actuator/info"
    );

    private final LeaderElectionService leaderElection;

    public LeaderGuardFilter(LeaderElectionService leaderElection) {
        this.leaderElection = leaderElection;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req,
                                    HttpServletResponse res,
                                    FilterChain chain) throws ServletException, IOException {

        String method = req.getMethod();
        String path = req.getRequestURI();

        if (ALWAYS_ALLOWED_PATHS.contains(path) || !MUTATING_METHODS.contains(method)) {
            chain.doFilter(req, res);
            return;
        }

        if (leaderElection.isLeader()) {
            chain.doFilter(req, res);
            return;
        }

        log.warn("Rejecting {} {} — this replica is not the {} leader",
                method, path, leaderElection.getServiceName());
        res.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        res.setHeader("X-Leader-Status", "standby");
        res.setContentType("application/json");
        res.getWriter().write(
                "{\"error\":\"not leader\",\"service\":\"" + leaderElection.getServiceName() + "\"}"
        );
    }
}
