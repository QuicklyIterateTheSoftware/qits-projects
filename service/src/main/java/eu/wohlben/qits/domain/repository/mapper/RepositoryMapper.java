package eu.wohlben.qits.domain.repository.mapper;

import eu.wohlben.qits.domain.repository.dto.RepositoryDto;
import eu.wohlben.qits.domain.repository.entity.Repository;
import org.mapstruct.Mapper;

@Mapper(componentModel = "cdi")
public interface RepositoryMapper {

    RepositoryDto toDto(Repository entity);
}
