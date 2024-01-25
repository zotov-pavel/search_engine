package searchengine.services;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import searchengine.config.JsoupConnection;
import searchengine.config.Site;
import searchengine.config.SitesList;
import searchengine.dto.BaseResponse;
import searchengine.exceptions.IncorrectMethodCallException;
import searchengine.model.IndexingStatus;
import searchengine.model.SearchStatus;
import searchengine.model.SiteEntity;
import searchengine.repositories.PageRepository;
import searchengine.repositories.SiteRepository;
import searchengine.utils.PageSaver;
import searchengine.utils.PageWalker;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;

@Service
@RequiredArgsConstructor
public class IndexingSiteServiceImpl implements IndexingSiteService {
    private static final String START_INDEXING_ERROR_MESSAGE = "Индексация уже запущена";
    private static final String STOP_INDEXING_ERROR_MESSAGE = "Индексация не запущена";
    private final SitesList sites;
    private final JsoupConnection jsoupConnection;
    private final SiteRepository siteRepository;
    private final PageRepository pageRepository;
    private ForkJoinPool forkJoinPool;
    private ExecutorService walkSiteExecutor;
    private ExecutorService pageSaveExecutor;
    private IndexingStatus indexingStatus = IndexingStatus.STOPPED;
    private Thread checkIndexingStatus;


    @Override
    public BaseResponse startIndexing() {
        if (indexingStatus.equals(IndexingStatus.RUNNING)) {
            throw new IncorrectMethodCallException(START_INDEXING_ERROR_MESSAGE);
        }
        List<Site> sitesData = sites.getSites();
        initThreadPools(sitesData.size());
        indexingStatus = IndexingStatus.RUNNING;
        checkIndexingStatus.start();
        for (Site site : sites.getSites()) {
            deleteSiteDataFromDatabase(site);
            SiteEntity siteEntity = saveSiteEntityToDatabase(site);
            saveAllPagesToDataBase(siteEntity);
        }
        BaseResponse response = new BaseResponse();
        response.setResult(true);
        return response;
    }

    @Override
    public BaseResponse stopIndexing() {
        if (indexingStatus.equals(IndexingStatus.STOPPED)) {
            throw new IncorrectMethodCallException(STOP_INDEXING_ERROR_MESSAGE);
        }
        forkJoinPool.shutdownNow();
        BaseResponse response = new BaseResponse();
        response.setResult(true);
        return response;
    }

    private void initThreadPools(int size) {
        forkJoinPool = new ForkJoinPool();
        walkSiteExecutor = Executors.newFixedThreadPool(size);
        pageSaveExecutor = Executors.newFixedThreadPool(size);
        checkIndexingStatus = new Thread(() -> {
            long start = System.currentTimeMillis();
            while (true) {
                if(walkSiteExecutor.isTerminated() || forkJoinPool.isShutdown()) {
                    indexingStatus = IndexingStatus.STOPPED;
                    break;
                }
            }

            long end = (System.currentTimeMillis() - start) / 1000;
            long min = end / 60;
            long sec = end % 60;
            System.out.printf("%d min %d sec\n", min, sec);
        });
    }

    private void deleteSiteDataFromDatabase(Site site) {
        siteRepository.deleteByUrl(site.getUrl());
    }

    private SiteEntity saveSiteEntityToDatabase(Site site) {
        String siteUrl = site.getUrl();
        siteUrl = siteUrl.endsWith("/") ? siteUrl : siteUrl.concat("/");
        SiteEntity siteEntity = SiteEntity.builder()
                .status(SearchStatus.INDEXING)
                .statusTime(LocalDateTime.now())
                .name(site.getName())
                .url(siteUrl)
                .build();
        return siteRepository.save(siteEntity);
    }

    private void saveAllPagesToDataBase(SiteEntity siteEntity) {
        PageWalker walker = new PageWalker(siteEntity, siteRepository, jsoupConnection, siteEntity.getUrl());
        walker.setRunning(true);
        walkSiteExecutor.execute(() -> {
            try {
                forkJoinPool.invoke(walker);
                siteRepository.updateSearchStatus(SearchStatus.INDEXED.name(), siteEntity.getId());
                pageRepository.saveAll(siteEntity.getPages());
            } catch (RuntimeException e) {
                siteRepository.updateSearchStatus(SearchStatus.FAILED.name(), siteEntity.getId());
                pageRepository.saveAll(siteEntity.getPages());
            } finally {
                walker.setRunning(false);
                walkSiteExecutor.shutdown();
                pageSaveExecutor.shutdown();
            }
        });
        pageSaveExecutor.execute(new PageSaver(walker, pageRepository));
    }
}
