package com.aiknowledge.knowledge.config;

import com.aiknowledge.knowledge.analysis.AiDocumentAnalysis;
import com.aiknowledge.knowledge.analysis.AiKnowledgeIndex;
import com.aiknowledge.knowledge.analysis.KnowledgeAnalysisRunner;
import com.aiknowledge.knowledge.store.KnowledgeStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AnalysisConfig {
    @Bean
    KnowledgeAnalysisRunner knowledgeAnalysisRunner(KnowledgeStore store) {
        return new KnowledgeAnalysisRunner(store, new AiDocumentAnalysis());
    }

    @Bean
    AiKnowledgeIndex aiKnowledgeIndex() {
        return new AiKnowledgeIndex();
    }
}
