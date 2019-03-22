package top.ezttf.graduation.dao.mysql;

import org.springframework.data.jpa.repository.JpaRepository;
import top.ezttf.graduation.pojo.MySqlRowData;

/**
 * @author yuwen
 * @date 2019/3/6
 */
public interface RowDataRepository extends JpaRepository<MySqlRowData, Long> {

}
